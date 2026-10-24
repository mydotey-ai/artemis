package org.mydotey.ai.artemis.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mydotey.ai.artemis.proto.common.v1.Instance;
import org.mydotey.ai.artemis.proto.common.v1.ServiceKey;

/**
 * 消费方 SDK 端到端（进程内 fake discovery：真实 gRPC + 真实 HTTP 节点目录）：
 * bootstrap+推送、多订阅隔离、断连收敛、地址熔断换址、快照回放、全挂兜底、checksum 自愈。
 */
class DiscoveryClientIT {

    private static final String CLUSTER = "test-cluster";
    private static final ServiceKey ORDER = ServiceKey.newBuilder()
            .setNamespace("default")
            .setService("order-service")
            .build();
    private static final ServiceKey PAY = ServiceKey.newBuilder()
            .setNamespace("default")
            .setService("pay-service")
            .build();

    @TempDir
    Path tempDir;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private FakeDiscoveryServer server;
    private FakeDiscoveryServer second;
    private DiscoveryClient client;

    @BeforeEach
    void setUp() throws IOException {
        System.setProperty("artemis.client.clientId", "it");
        System.setProperty("artemis.client.discovery.snapshot.dir", tempDir.toString());
        System.setProperty("artemis.client.discovery.reconnect.baseDelayMs", "100");
        System.setProperty("artemis.client.discovery.reconnect.maxDelayMs", "1000");
        System.setProperty("artemis.client.discovery.address.refreshIntervalMs", "1000");
        System.setProperty("artemis.client.discovery.address.retryBaseMs", "100");
        System.setProperty("artemis.client.discovery.address.retryMaxMs", "1000");
        System.setProperty("artemis.client.discovery.listener.queueCapacity", "100");
        System.setProperty("artemis.client.discovery.reconcile.intervalMs", "600000");
        System.setProperty("artemis.client.discovery.refresh.intervalMs", "600000");

        server = new FakeDiscoveryServer();
        server.start();
        server.advertise(server.grpcAddress());
        System.setProperty("artemis.client.discovery.addresses", server.httpUrl());
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (second != null) {
            second.stop();
        }
        server.stop();
        System.clearProperty("artemis.client.clientId");
        System.clearProperty("artemis.client.discovery.addresses");
        System.clearProperty("artemis.client.discovery.snapshot.dir");
        System.clearProperty("artemis.client.discovery.reconnect.baseDelayMs");
        System.clearProperty("artemis.client.discovery.reconnect.maxDelayMs");
        System.clearProperty("artemis.client.discovery.address.refreshIntervalMs");
        System.clearProperty("artemis.client.discovery.address.retryBaseMs");
        System.clearProperty("artemis.client.discovery.address.retryMaxMs");
        System.clearProperty("artemis.client.discovery.listener.queueCapacity");
        System.clearProperty("artemis.client.discovery.reconcile.intervalMs");
        System.clearProperty("artemis.client.discovery.refresh.intervalMs");
    }

    @Test
    void should_seed_and_apply_subscription_push() {
        server.register(CLUSTER, ORDER, instance("i1", 8080));
        List<ServiceId> notified = new CopyOnWriteArrayList<>();

        client = newClient();
        client.start();
        Subscription subscription = client.subscribe(selector(ORDER), notified::add);

        TestAwait.await(
                "bootstrap",
                5_000,
                () -> subscription.getInstances(service(ORDER)).size() == 1);
        TestAwait.await("connected", 5_000, () -> subscription.status().isConnected());
        assertThat(subscription.status().isSnapshotReplay()).isFalse();

        server.pushUpdate(CLUSTER, ORDER, instance("i1", 9090));
        TestAwait.await(
                "update applied",
                5_000,
                () -> subscription.getInstances(service(ORDER)).get(0).getPort() == 9090);
        assertThat(notified).contains(service(ORDER));

        server.pushDelete(CLUSTER, ORDER, "i1");
        TestAwait.await(
                "delete applied",
                5_000,
                () -> subscription.getInstances(service(ORDER)).isEmpty());

        subscription.close();
        assertThatThrownBy(subscription::status).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> subscription.getInstances(service(ORDER))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void should_isolate_views_and_snapshots_between_subscriptions() throws Exception {
        server.register(CLUSTER, ORDER, instance("i1", 8080));
        server.register(CLUSTER, PAY, instance("p1", 8080));

        client = newClient();
        client.start();
        Subscription order = client.subscribe(selector(ORDER));
        Subscription pay = client.subscribe(selector(PAY));

        TestAwait.await(
                "both seeded",
                5_000,
                () -> order.getInstances(service(ORDER)).size() == 1
                        && pay.getInstances(service(PAY)).size() == 1);
        assertThat(order.getInstances(service(PAY))).isEmpty();
        assertThat(pay.getInstances(service(ORDER))).isEmpty();

        TestAwait.await("two snapshot files", 5_000, () -> {
            try {
                return snapshotFiles() >= 2;
            } catch (IOException e) {
                return false;
            }
        });
    }

    @Test
    void should_reconnect_after_stream_break_and_converge() throws Exception {
        server.register(CLUSTER, ORDER, instance("i1", 8080));
        server.register(CLUSTER, PAY, instance("p1", 8080));

        client = newClient();
        client.start();
        Subscription subscription = client.subscribe(selector(ORDER, PAY));
        TestAwait.await(
                "both seeded",
                5_000,
                () -> subscription.getInstances(service(ORDER)).size() == 1
                        && subscription.getInstances(service(PAY)).size() == 1);

        server.rejectSubscribe = true;
        server.breakStreams();
        TestAwait.await("disconnected", 3_000, () -> !subscription.status().isConnected());
        server.remove(CLUSTER, PAY); // 断连期间消失的服务
        Thread.sleep(300); // 让客户端在拒绝期完成一次重连尝试
        server.rejectSubscribe = false;

        TestAwait.await(
                "converged after reconnect",
                8_000,
                () -> subscription.status().isConnected()
                        && subscription.getInstances(service(PAY)).isEmpty());
        assertThat(subscription.getInstances(service(ORDER))).hasSize(1);
    }

    @Test
    void should_fail_over_to_next_address_when_current_server_down() throws Exception {
        server.register(CLUSTER, ORDER, instance("i1", 8080));
        second = new FakeDiscoveryServer();
        second.start();
        second.advertise(second.grpcAddress());
        second.register(CLUSTER, ORDER, instance("i2", 8080));

        client = newClient();
        client.start();
        Subscription subscription = client.subscribe(selector(ORDER));
        TestAwait.await(
                "seeded from first",
                5_000,
                () -> subscription.getInstances(service(ORDER)).size() == 1
                        && "i1"
                                .equals(subscription
                                        .getInstances(service(ORDER))
                                        .get(0)
                                        .getInstanceId()));

        server.advertise(second.grpcAddress(), server.grpcAddress()); // 节点目录加入第二节点
        Thread.sleep(1200); // 等节点列表刷新
        server.stopGrpc();

        TestAwait.await(
                "failed over to second",
                10_000,
                () -> subscription.getInstances(service(ORDER)).size() == 1
                        && "i2"
                                .equals(subscription
                                        .getInstances(service(ORDER))
                                        .get(0)
                                        .getInstanceId()));
    }

    @Test
    void should_replay_snapshot_on_cold_start_when_server_down() {
        server.register(CLUSTER, ORDER, instance("i1", 8080));
        client = newClient();
        client.start();
        Subscription first = client.subscribe(selector(ORDER));
        TestAwait.await(
                "seeded", 5_000, () -> first.getInstances(service(ORDER)).size() == 1);
        client.close();
        client = null;

        server.stop();

        client = newClient();
        client.start();
        Subscription replayed = client.subscribe(selector(ORDER));
        assertThat(replayed.getInstances(service(ORDER))).hasSize(1);
        assertThat(replayed.status().isSnapshotReplay()).isTrue();
        assertThat(replayed.status().snapshotTimestampMs()).isPositive();
    }

    @Test
    void should_keep_last_known_instances_when_all_servers_down() {
        server.register(CLUSTER, ORDER, instance("i1", 8080));
        client = newClient();
        client.start();
        Subscription subscription = client.subscribe(selector(ORDER));
        TestAwait.await(
                "seeded", 5_000, () -> subscription.getInstances(service(ORDER)).size() == 1);

        server.stop();
        TestAwait.await("disconnected", 5_000, () -> !subscription.status().isConnected());
        assertThat(subscription.getInstances(service(ORDER))).hasSize(1);
    }

    @Test
    void should_full_refresh_when_checksum_mismatch() {
        System.setProperty("artemis.client.discovery.reconcile.intervalMs", "1000");
        server.register(CLUSTER, ORDER, instance("i1", 8080));

        client = newClient();
        client.start();
        Subscription subscription = client.subscribe(selector(ORDER));
        TestAwait.await(
                "seeded", 5_000, () -> subscription.getInstances(service(ORDER)).size() == 1);

        server.replace(CLUSTER, ORDER, instance("i2", 8080)); // 模拟丢失的推送
        TestAwait.await("checksum requested", 5_000, () -> server.checksumRequests.get() > 0);
        TestAwait.await(
                "checksum mismatch triggers refresh",
                8_000,
                () -> subscription.getInstances(service(ORDER)).size() == 1
                        && "i2"
                                .equals(subscription
                                        .getInstances(service(ORDER))
                                        .get(0)
                                        .getInstanceId()));
    }

    private DiscoveryClient newClient() {
        return ArtemisClients.newDiscoveryClient(TestProperties.systemProperties(), registry);
    }

    private int snapshotFiles() throws IOException {
        Path dir = tempDir.resolve("it");
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return (int) files.filter(path -> path.getFileName().toString().startsWith("selector-"))
                    .count();
        }
    }

    private static DiscoverySelector selector(ServiceKey... keys) {
        DiscoverySelector.Builder builder = DiscoverySelector.newBuilder();
        for (ServiceKey key : keys) {
            builder.addService(service(key));
        }
        return builder.build();
    }

    private static ServiceId service(ServiceKey key) {
        return ServiceId.newBuilder()
                .namespace(key.getNamespace())
                .service(key.getService())
                .build();
    }

    private static Instance instance(String instanceId, int port) {
        return Instance.newBuilder()
                .setInstanceId(instanceId)
                .setHost("10.0.0.1")
                .setPort(port)
                .setWeight(100)
                .setStatus(org.mydotey.ai.artemis.proto.common.v1.InstanceStatus.INSTANCE_STATUS_UP)
                .build();
    }
}
