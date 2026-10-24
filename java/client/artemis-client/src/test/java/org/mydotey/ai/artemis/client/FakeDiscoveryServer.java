package org.mydotey.ai.artemis.client;

import com.sun.net.httpserver.HttpServer;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.Instance;
import org.mydotey.ai.artemis.proto.common.v1.ResponseStatus;
import org.mydotey.ai.artemis.proto.common.v1.ServiceInstances;
import org.mydotey.ai.artemis.proto.common.v1.ServiceKey;
import org.mydotey.ai.artemis.proto.discovery.v1.ChecksumRequest;
import org.mydotey.ai.artemis.proto.discovery.v1.ChecksumResponse;
import org.mydotey.ai.artemis.proto.discovery.v1.ChecksumResponseData;
import org.mydotey.ai.artemis.proto.discovery.v1.ClusterChecksums;
import org.mydotey.ai.artemis.proto.discovery.v1.ClusterServiceInstances;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryNode;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryNodesResponse;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryQueryServiceGrpc;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryReconciliationServiceGrpc;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoverySubscriptionServiceGrpc;
import org.mydotey.ai.artemis.proto.discovery.v1.InstanceChange;
import org.mydotey.ai.artemis.proto.discovery.v1.QueryRequest;
import org.mydotey.ai.artemis.proto.discovery.v1.QueryResponse;
import org.mydotey.ai.artemis.proto.discovery.v1.QueryResponseData;
import org.mydotey.ai.artemis.proto.discovery.v1.ServiceChange;
import org.mydotey.ai.artemis.proto.discovery.v1.ServiceChecksum;
import org.mydotey.ai.artemis.proto.discovery.v1.SubscribeRequest;
import org.mydotey.ai.artemis.proto.discovery.v1.SubscriptionEvent;
import org.mydotey.ai.artemis.proto.discovery.v1.UpstreamStatus;
import org.mydotey.ai.artemis.proto.discovery.v1.UpstreamSyncState;

/**
 * 进程内可编程假 discovery：真实 gRPC（查询/对账/订阅）+ 真实 HTTP {@code /cluster/nodes}
 * 节点目录（ADR-020）。支持编程数据、主动推送、断流、拒绝订阅与就绪切换，供 SDK 端到端用例驱动。
 */
public final class FakeDiscoveryServer {

    private final Map<String, Map<ServiceKey, List<Instance>>> data = new ConcurrentHashMap<>();
    private final List<StreamObserver<SubscriptionEvent>> subscribers = new CopyOnWriteArrayList<>();
    private final List<String> nodes = new CopyOnWriteArrayList<>();

    public volatile boolean notReady;
    public volatile boolean rejectSubscribe;
    public volatile boolean forceChecksumMismatch;
    public final java.util.concurrent.atomic.AtomicInteger queryRequests =
            new java.util.concurrent.atomic.AtomicInteger();
    public final java.util.concurrent.atomic.AtomicInteger checksumRequests =
            new java.util.concurrent.atomic.AtomicInteger();

    private volatile Server grpc;
    private volatile HttpServer http;

    public void start() throws IOException {
        startGrpc();
        startHttp();
    }

    public void startGrpc() throws IOException {
        grpc = ServerBuilder.forPort(0)
                .addService(new DiscoveryQueryServiceGrpc.DiscoveryQueryServiceImplBase() {
                    @Override
                    public void query(QueryRequest request, StreamObserver<QueryResponse> observer) {
                        queryRequests.incrementAndGet();
                        if (notReady) {
                            observer.onError(Status.UNAVAILABLE
                                    .withDescription("not ready")
                                    .asException());
                            return;
                        }
                        observer.onNext(QueryResponse.newBuilder()
                                .setStatus(success())
                                .setData(QueryResponseData.newBuilder()
                                        .addAllServices(queryEntries(request))
                                        .addUpstreams(upstream()))
                                .build());
                        observer.onCompleted();
                    }
                })
                .addService(new DiscoveryReconciliationServiceGrpc.DiscoveryReconciliationServiceImplBase() {
                    @Override
                    public void checksum(ChecksumRequest request, StreamObserver<ChecksumResponse> observer) {
                        checksumRequests.incrementAndGet();
                        if (notReady) {
                            observer.onError(Status.UNAVAILABLE
                                    .withDescription("not ready")
                                    .asException());
                            return;
                        }
                        observer.onNext(ChecksumResponse.newBuilder()
                                .setStatus(success())
                                .setData(ChecksumResponseData.newBuilder()
                                        .addAllClusters(checksums(request))
                                        .addUpstreams(upstream()))
                                .build());
                        observer.onCompleted();
                    }
                })
                .addService(new DiscoverySubscriptionServiceGrpc.DiscoverySubscriptionServiceImplBase() {
                    @Override
                    public void subscribe(SubscribeRequest request, StreamObserver<SubscriptionEvent> observer) {
                        if (notReady || rejectSubscribe) {
                            observer.onError(Status.UNAVAILABLE
                                    .withDescription("not ready")
                                    .asException());
                            return;
                        }
                        observer.onNext(SubscriptionEvent.getDefaultInstance()); // 确认
                        for (ClusterServiceInstances entry : queryEntries(request)) {
                            ServiceChange.Builder batch = ServiceChange.newBuilder()
                                    .setKey(entry.getInstances().getKey());
                            for (Instance instance : entry.getInstances().getInstancesList()) {
                                batch.addChanges(InstanceChange.newBuilder()
                                        .setInstance(instance)
                                        .setType(org.mydotey.ai.artemis.proto.discovery.v1.ChangeType.CHANGE_TYPE_NEW));
                            }
                            observer.onNext(SubscriptionEvent.newBuilder()
                                    .setCluster(entry.getCluster())
                                    .setService(batch)
                                    .build());
                        }
                        subscribers.add(observer);
                        if (observer instanceof ServerCallStreamObserver) {
                            ((ServerCallStreamObserver<SubscriptionEvent>) observer)
                                    .setOnCancelHandler(() -> subscribers.remove(observer));
                        }
                    }
                })
                .build()
                .start();
    }

    public void startHttp() throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/cluster/nodes", exchange -> {
            if (notReady) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            DiscoveryNodesResponse.Builder response =
                    DiscoveryNodesResponse.newBuilder().setStatus(success());
            for (String node : nodes) {
                DiscoveryNode parsed = parseNode(node);
                if (parsed != null) {
                    response.addNodes(parsed);
                }
            }
            byte[] body = response.build().toByteArray();
            exchange.getResponseHeaders().set("Content-Type", "application/x-protobuf");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        http.start();
    }

    public String grpcAddress() {
        return "127.0.0.1:" + grpc.getPort();
    }

    public String httpUrl() {
        return "http://127.0.0.1:" + http.getAddress().getPort();
    }

    public void advertise(String... addresses) {
        nodes.clear();
        nodes.addAll(Arrays.asList(addresses));
    }

    public void register(String cluster, ServiceKey key, Instance... instances) {
        clusterData(cluster).put(key, new CopyOnWriteArrayList<>(Arrays.asList(instances)));
    }

    public void replace(String cluster, ServiceKey key, Instance... instances) {
        clusterData(cluster).put(key, new CopyOnWriteArrayList<>(Arrays.asList(instances)));
    }

    public void remove(String cluster, ServiceKey key) {
        Map<ServiceKey, List<Instance>> clusterMap = data.get(cluster);
        if (clusterMap != null) {
            clusterMap.remove(key);
        }
    }

    public void pushUpdate(String cluster, ServiceKey key, Instance instance) {
        List<Instance> instances = clusterData(cluster).get(key);
        List<Instance> updated = instances == null ? new ArrayList<Instance>() : new ArrayList<>(instances);
        for (int i = 0; i < updated.size(); i++) {
            if (updated.get(i).getInstanceId().equals(instance.getInstanceId())) {
                updated.set(i, instance);
                clusterData(cluster).put(key, new CopyOnWriteArrayList<>(updated));
                broadcast(
                        cluster,
                        key,
                        instance,
                        org.mydotey.ai.artemis.proto.discovery.v1.ChangeType.CHANGE_TYPE_UPDATE);
                return;
            }
        }
        updated.add(instance);
        clusterData(cluster).put(key, new CopyOnWriteArrayList<>(updated));
        broadcast(cluster, key, instance, org.mydotey.ai.artemis.proto.discovery.v1.ChangeType.CHANGE_TYPE_NEW);
    }

    public void pushDelete(String cluster, ServiceKey key, String instanceId) {
        List<Instance> instances = clusterData(cluster).get(key);
        if (instances == null) {
            return;
        }
        List<Instance> updated = new ArrayList<>(instances);
        updated.removeIf(instance -> instance.getInstanceId().equals(instanceId));
        clusterData(cluster).put(key, new CopyOnWriteArrayList<>(updated));
        broadcast(
                cluster,
                key,
                Instance.newBuilder().setInstanceId(instanceId).build(),
                org.mydotey.ai.artemis.proto.discovery.v1.ChangeType.CHANGE_TYPE_DELETE);
    }

    public void breakStreams() {
        for (StreamObserver<SubscriptionEvent> subscriber : subscribers) {
            subscriber.onError(Status.UNAVAILABLE.withDescription("break").asException());
        }
        subscribers.clear();
    }

    public void stopGrpc() {
        Server current = grpc;
        if (current != null) {
            current.shutdownNow();
        }
    }

    public void stop() {
        stopGrpc();
        HttpServer current = http;
        if (current != null) {
            current.stop(0);
        }
    }

    private void broadcast(
            String cluster,
            ServiceKey key,
            Instance instance,
            org.mydotey.ai.artemis.proto.discovery.v1.ChangeType type) {
        SubscriptionEvent event = SubscriptionEvent.newBuilder()
                .setCluster(cluster)
                .setService(ServiceChange.newBuilder()
                        .setKey(key)
                        .addChanges(InstanceChange.newBuilder()
                                .setInstance(instance)
                                .setType(type)))
                .build();
        for (StreamObserver<SubscriptionEvent> subscriber : subscribers) {
            try {
                subscriber.onNext(event);
            } catch (RuntimeException ignored) {
                // 断开的订阅由后续断流清理
            }
        }
    }

    private Map<ServiceKey, List<Instance>> clusterData(String cluster) {
        return data.computeIfAbsent(cluster, ignored -> new ConcurrentHashMap<>());
    }

    private List<ClusterServiceInstances> queryEntries(QueryRequest request) {
        return queryEntries(request.getServicesList(), request.getLabelsMap(), request.getClustersList());
    }

    private List<ClusterServiceInstances> queryEntries(SubscribeRequest request) {
        return queryEntries(request.getServicesList(), request.getLabelsMap(), request.getClustersList());
    }

    private List<ClusterServiceInstances> queryEntries(
            List<ServiceKey> services, Map<String, String> labels, List<String> clusters) {
        List<ClusterServiceInstances> result = new ArrayList<>();
        for (Map.Entry<String, Map<ServiceKey, List<Instance>>> cluster : data.entrySet()) {
            if (!matchesCluster(clusters, cluster.getKey())) {
                continue;
            }
            for (Map.Entry<ServiceKey, List<Instance>> service :
                    cluster.getValue().entrySet()) {
                if (!services.isEmpty() && !services.contains(service.getKey())) {
                    continue;
                }
                List<Instance> matched = filterLabels(service.getValue(), labels);
                if (!matched.isEmpty()) {
                    result.add(ClusterServiceInstances.newBuilder()
                            .setCluster(cluster.getKey())
                            .setInstances(ServiceInstances.newBuilder()
                                    .setKey(service.getKey())
                                    .addAllInstances(matched))
                            .build());
                }
            }
        }
        return result;
    }

    private List<ClusterChecksums> checksums(ChecksumRequest request) {
        List<ClusterChecksums> result = new ArrayList<>();
        for (Map.Entry<String, Map<ServiceKey, List<Instance>>> cluster : data.entrySet()) {
            if (!matchesCluster(request.getClustersList(), cluster.getKey())) {
                continue;
            }
            ClusterChecksums.Builder entry = ClusterChecksums.newBuilder().setCluster(cluster.getKey());
            for (Map.Entry<ServiceKey, List<Instance>> service :
                    cluster.getValue().entrySet()) {
                if (!request.getServicesList().isEmpty()
                        && !request.getServicesList().contains(service.getKey())) {
                    continue;
                }
                List<Instance> matched = filterLabels(service.getValue(), request.getLabelsMap());
                if (!matched.isEmpty()) {
                    int checksum = checksum(matched);
                    entry.addServices(ServiceChecksum.newBuilder()
                            .setKey(service.getKey())
                            .setChecksum(forceChecksumMismatch ? checksum + 1 : checksum));
                }
            }
            if (entry.getServicesCount() > 0) {
                result.add(entry.build());
            }
        }
        return result;
    }

    private static boolean matchesCluster(List<String> requested, String cluster) {
        return requested.isEmpty() || requested.contains(cluster);
    }

    private static List<Instance> filterLabels(List<Instance> instances, Map<String, String> labels) {
        if (labels.isEmpty()) {
            return new ArrayList<>(instances);
        }
        List<Instance> matched = new ArrayList<>();
        for (Instance instance : instances) {
            boolean ok = true;
            for (Map.Entry<String, String> label : labels.entrySet()) {
                if (!label.getValue().equals(instance.getMetadataMap().get(label.getKey()))) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                matched.add(instance);
            }
        }
        return matched;
    }

    private static DiscoveryNode parseNode(String address) {
        int colon = address.lastIndexOf(':');
        if (colon <= 0 || colon == address.length() - 1) {
            return null;
        }
        try {
            String host = address.substring(0, colon);
            int port = Integer.parseInt(address.substring(colon + 1));
            return DiscoveryNode.newBuilder().setHost(host).setPort(port).build();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static UpstreamStatus upstream() {
        return UpstreamStatus.newBuilder()
                .setCluster("test-cluster")
                .setState(UpstreamSyncState.UPSTREAM_SYNC_STATE_SYNCED)
                .build();
    }

    private static ResponseStatus success() {
        return ResponseStatus.newBuilder().setAck(Ack.ACK_SUCCESS).build();
    }

    // ---- canonical checksum（与 api/discovery-reconciliation-api.md §3 一致，测试侧独立实现） ----

    private static int checksum(List<Instance> instances) {
        int checksum = 0;
        for (Instance instance : instances) {
            checksum ^= instanceHash(instance);
        }
        return checksum;
    }

    private static int instanceHash(Instance instance) {
        String canonical = instance.getInstanceId()
                + '\u0000'
                + instance.getHost()
                + '\u0000'
                + instance.getPort()
                + '\u0000'
                + statusName(instance.getStatus())
                + '\u0000'
                + instance.getWeight()
                + '\u0000';
        int metadata = 0;
        for (Map.Entry<String, String> entry : instance.getMetadataMap().entrySet()) {
            metadata ^= fnv1a32(entry.getKey() + '\u0000' + entry.getValue() + '\u0000');
        }
        return fnv1a32(canonical) ^ metadata;
    }

    private static String statusName(org.mydotey.ai.artemis.proto.common.v1.InstanceStatus status) {
        switch (status) {
            case INSTANCE_STATUS_UNKNOWN:
                return "UNKNOWN";
            case INSTANCE_STATUS_UP:
                return "UP";
            case INSTANCE_STATUS_DOWN:
                return "DOWN";
            case INSTANCE_STATUS_UNSPECIFIED:
            default:
                return "UNSPECIFIED";
        }
    }

    private static int fnv1a32(String value) {
        int hash = 0x811c9dc5;
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xff;
            hash *= 0x01000193;
        }
        return hash;
    }
}
