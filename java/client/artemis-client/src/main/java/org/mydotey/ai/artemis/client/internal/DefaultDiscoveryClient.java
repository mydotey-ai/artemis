package org.mydotey.ai.artemis.client.internal;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.mydotey.ai.artemis.client.DiscoveryClient;
import org.mydotey.ai.artemis.client.DiscoveryListener;
import org.mydotey.ai.artemis.client.DiscoverySelector;
import org.mydotey.ai.artemis.client.Subscription;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryNode;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryNodesResponse;
import org.mydotey.scf.facade.StringProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 发现 SDK 默认实现（internal，经 {@code ArtemisClients.newDiscoveryClient(...)} 构造）：
 * client 级共享前置寻址（HTTP 引导地址 → 节点目录 → 熔断选址，ADR-020）、gRPC 传输、
 * applier/调度/监听分发/快照 writer；每订阅独立流、视图与快照（见 {@link DiscoverySubscription}）。
 */
public final class DefaultDiscoveryClient implements DiscoveryClient {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultDiscoveryClient.class);

    private static final int APPLIER_QUEUE = 8_192;
    private static final int SCHEDULER_QUEUE = 256;
    private static final long CLOSE_AWAIT_MS = 5_000;

    private enum State {
        NEW,
        RUNNING,
        CLOSED
    }

    private final StringProperties properties;
    private final MeterRegistry registry;
    private final String clientId;
    private final GrpcDiscoveryClient grpcClient;
    private final NodeListRefresher nodeListRefresher;
    private final BoundedScheduledExecutor applier;
    private final BoundedScheduledExecutor scheduler;
    private final ListenerDispatcher listenerDispatcher;
    private final DiscoverySnapshotStore snapshotStore;
    private final DiscoverySnapshotWriter snapshotWriter;
    private final Set<DiscoverySubscription> subscriptions =
            Collections.newSetFromMap(new ConcurrentHashMap<DiscoverySubscription, Boolean>());
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);

    public DefaultDiscoveryClient(StringProperties properties, MeterRegistry registry) {
        this.properties = properties;
        this.registry = registry;
        this.clientId = ClientProperties.clientId(properties);

        FailoverAddressProvider addressProvider = new FailoverAddressProvider(
                properties, registry, clientId, FailoverAddressProvider.DISCOVERY_ADDRESS_PREFIX, true);
        this.grpcClient = new GrpcDiscoveryClient(addressProvider, properties);
        HttpNodeListFetcher fetcher =
                new HttpNodeListFetcher(properties, HttpNodeListFetcher.KEY_DISCOVERY_ADDRESSES, discoveryDecoder());
        this.nodeListRefresher = new NodeListRefresher(
                fetcher,
                addressProvider,
                properties,
                registry,
                clientId,
                FailoverAddressProvider.DISCOVERY_ADDRESS_PREFIX,
                "discovery-address");
        addressProvider.setCircuitListener(new Runnable() {
            @Override
            public void run() {
                nodeListRefresher.refreshNow();
            }
        });

        this.applier = new BoundedScheduledExecutor(
                "discovery-apply", 1, APPLIER_QUEUE, new NamedDaemonThreadFactory("artemis-client-discovery-apply"));
        this.scheduler = new BoundedScheduledExecutor(
                "discovery-scheduler",
                1,
                SCHEDULER_QUEUE,
                new NamedDaemonThreadFactory("artemis-client-discovery-scheduler"));
        this.listenerDispatcher = new ListenerDispatcher(properties, registry, clientId);
        this.snapshotStore = new DiscoverySnapshotStore(properties, clientId, registry);
        this.snapshotWriter = new DiscoverySnapshotWriter();
    }

    /** discovery 节点目录解析器（ADR-020）：所有条目为 UP 候选，无探测态。 */
    static HttpNodeListFetcher.ResponseDecoder discoveryDecoder() {
        return new HttpNodeListFetcher.ResponseDecoder() {
            @Override
            public List<NodeEndpoint> decode(byte[] body) throws Exception {
                DiscoveryNodesResponse response = DiscoveryNodesResponse.parseFrom(body);
                List<NodeEndpoint> endpoints = new ArrayList<>();
                for (DiscoveryNode node : response.getNodesList()) {
                    if (node.getHost() != null
                            && !node.getHost().isEmpty()
                            && node.getPort() >= 1
                            && node.getPort() <= 65535) {
                        endpoints.add(
                                new NodeEndpoint(node.getHost() + ":" + node.getPort(), NodeEndpoint.PRIORITY_UP));
                    }
                }
                return endpoints;
            }
        };
    }

    @Override
    public void start() {
        if (!state.compareAndSet(State.NEW, State.RUNNING)) {
            throw new IllegalStateException("discovery client already started or closed");
        }
        nodeListRefresher.start();
        LOG.info("artemis discovery client started ({})", clientId);
    }

    @Override
    public Subscription subscribe(DiscoverySelector selector, DiscoveryListener listener) {
        Objects.requireNonNull(selector, "selector");
        if (state.get() != State.RUNNING) {
            throw new IllegalStateException("discovery client not started or closed");
        }
        DiscoverySubscription subscription = new DiscoverySubscription(this, selector, listener);
        subscriptions.add(subscription);
        LOG.info("discovery subscription created ({}), selector={}", subscriptionTag(selector), selector);
        return subscription;
    }

    @Override
    public void close() {
        State previous = state.getAndSet(State.CLOSED);
        if (previous == State.CLOSED) {
            return;
        }
        for (DiscoverySubscription subscription : new ArrayList<>(subscriptions)) {
            try {
                subscription.close();
            } catch (RuntimeException e) {
                LOG.warn("discovery subscription close failed: {}", e.toString());
            }
        }
        snapshotWriter.close();
        nodeListRefresher.close();
        scheduler.shutdownNow();
        applier.shutdown();
        try {
            if (!applier.awaitTermination(CLOSE_AWAIT_MS, TimeUnit.MILLISECONDS)) {
                applier.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            applier.shutdownNow();
        }
        listenerDispatcher.close();
        grpcClient.close();
        LOG.info("artemis discovery client closed ({})", clientId);
    }

    /** applier 入队（有界）；队列满返回 false（调用方由对账/周期刷新兜底）。 */
    boolean enqueue(Runnable task) {
        if (state.get() != State.RUNNING) {
            return false;
        }
        try {
            applier.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            registry.counter("artemis.client.discovery.apply.dropped", "client", clientId)
                    .increment();
            LOG.warn("discovery applier queue full ({}), task dropped", APPLIER_QUEUE);
            return false;
        }
    }

    /** close 期屏障入队：绕过 client 状态检查（client 已置 CLOSED 但 applier 仍在跑）。 */
    boolean enqueueForClose(Runnable task) {
        try {
            applier.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    void subscriptionClosed(DiscoverySubscription subscription) {
        subscriptions.remove(subscription);
    }

    StringProperties properties() {
        return properties;
    }

    MeterRegistry registry() {
        return registry;
    }

    String clientId() {
        return clientId;
    }

    GrpcDiscoveryClient grpc() {
        return grpcClient;
    }

    BoundedScheduledExecutor scheduler() {
        return scheduler;
    }

    DiscoverySnapshotStore snapshotStore() {
        return snapshotStore;
    }

    DiscoverySnapshotWriter snapshotWriter() {
        return snapshotWriter;
    }

    ListenerDispatcher listenerDispatcher() {
        return listenerDispatcher;
    }

    private static String subscriptionTag(DiscoverySelector selector) {
        return selector.fingerprint();
    }
}
