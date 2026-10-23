package org.mydotey.ai.artemis.client.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Metrics;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.client.ArtemisClient;
import org.mydotey.ai.artemis.client.FakeRegistryServer;
import org.mydotey.ai.artemis.client.InstanceId;
import org.mydotey.ai.artemis.client.ServiceId;
import org.mydotey.ai.artemis.client.ServiceInstance;
import org.mydotey.ai.artemis.client.TestAwait;
import org.mydotey.ai.artemis.proto.common.v1.Instance;
import org.mydotey.ai.artemis.proto.common.v1.ServiceInstances;
import org.mydotey.ai.artemis.proto.registry.v1.HeartbeatRequest;

/** SDK 公共行为（fire-and-forget）：操作不返回成败，断言一律经假 gRPC 服务观察上报与自愈。 */
class DefaultArtemisClientTest {

    private static final ServiceId ORDER =
            ServiceId.newBuilder().namespace("default").service("order-service").build();

    private FakeRegistryServer server;
    private ArtemisClient client;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("artemis.client.heartbeat.intervalMs", "500");
        System.setProperty("artemis.client.reconnect.baseDelayMs", "100");
        System.setProperty("artemis.client.reconnect.maxDelayMs", "2000");
        server = new FakeRegistryServer();
        server.start();
        client = newClient(Metrics.globalRegistry, fixedFetcher(server.address()));
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        server.stop();
        System.clearProperty("artemis.client.heartbeat.intervalMs");
        System.clearProperty("artemis.client.reconnect.baseDelayMs");
        System.clearProperty("artemis.client.reconnect.maxDelayMs");
    }

    /** 测试构造：注入 fake 寻址传输（节点列表直供，绕过 HTTP 发现）。 */
    private static ArtemisClient newClient(
            io.micrometer.core.instrument.MeterRegistry registry, NodeListFetcher fetcher) {
        return new DefaultArtemisClient(systemProperties(), registry, fetcher);
    }

    private static NodeListFetcher fixedFetcher(final String... addresses) {
        return new NodeListFetcher() {
            @Override
            public List<NodeEndpoint> fetch() {
                return endpoints(addresses);
            }
        };
    }

    private static List<NodeEndpoint> endpoints(String... addresses) {
        List<NodeEndpoint> endpoints = new ArrayList<>();
        for (String address : addresses) {
            endpoints.add(new NodeEndpoint(address, NodeEndpoint.PRIORITY_UP));
        }
        return endpoints;
    }

    private static ServiceInstance instance(String instanceId, String host) {
        return ServiceInstance.newBuilder()
                .service(ORDER)
                .instanceId(instanceId)
                .host(host)
                .port(8080)
                .weight(100)
                .build();
    }

    private static ServiceInstance instance(String instanceId) {
        return instance(instanceId, "10.0.0.1");
    }

    @Test
    void should_reportRegister_when_register() {
        client.start();
        client.register(instance("i-1", "10.0.0.1"));

        TestAwait.await("register reported", 3000, () -> !server.registerRequests.isEmpty());
        ServiceInstances reported = server.registerRequests.get(0).getServices(0);
        assertThat(reported.getKey().getNamespace()).isEqualTo("default");
        assertThat(reported.getKey().getService()).isEqualTo("order-service");
        Instance reportedInstance = reported.getInstances(0);
        assertThat(reportedInstance.getInstanceId()).isEqualTo("i-1");
        assertThat(reportedInstance.getHost()).isEqualTo("10.0.0.1");
        assertThat(reportedInstance.getPort()).isEqualTo(8080);
        assertThat(reportedInstance.getStatus())
                .isEqualTo(org.mydotey.ai.artemis.proto.common.v1.InstanceStatus.INSTANCE_STATUS_UP);

        TestAwait.await(
                "heartbeat carries registered instance",
                3000,
                () -> server.heartbeatRequests.stream().anyMatch(r -> containsInstance(r, "i-1")));
    }

    @Test
    void should_selfHealViaHeartbeat_when_reportFails() {
        client.start();
        server.rejectAll = true;

        // 上报失败不向用户传导（void 不抛），本地集仍持有实例
        assertThatCode(() -> client.register(instance("i-1"))).doesNotThrowAnyException();
        server.rejectAll = false;
        // 失败触发即时心跳补投 + 周期心跳兜底：全量上报持续携带该实例（服务端 upsert 自愈通道）
        TestAwait.await(
                "heartbeat self-heals failed register",
                3000,
                () -> server.heartbeatRequests.stream().anyMatch(r -> containsInstance(r, "i-1")));
    }

    @Test
    void should_retryReport_when_transientFailure() throws Exception {
        System.setProperty("artemis.client.operation.retryIntervalMs", "100");
        io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        ArtemisClient retryingClient = newClient(meters, fixedFetcher(server.address()));
        try {
            retryingClient.start();
            server.rejectAll = true; // UNAVAILABLE：瞬态可重试
            retryingClient.register(instance("i-1"));

            // 有界重试：首试 + 至少一次重试到达服务端（重试间隔 100ms）
            TestAwait.await("retries issued", 3000, () -> server.registerRequests.size() >= 2);

            // 重试耗尽（2 次）即计入 operation.failure counter（Micrometer 标签维度）
            TestAwait.await(
                    "failure metric counted",
                    3000,
                    () -> meters.find("artemis.client.operation.failure")
                                            .tag("operation", "register")
                                            .counter()
                                    != null
                            && meters.find("artemis.client.operation.failure")
                                            .tag("operation", "register")
                                            .counter()
                                            .count()
                                    >= 1);

            // 恢复后兜底心跳接管，此后不再重试（数量稳定）
            server.rejectAll = false;
            Thread.sleep(600);
            int settled = server.registerRequests.size();
            assertThat(settled).isGreaterThanOrEqualTo(2);
            Thread.sleep(400);
            assertThat(server.registerRequests.size()).isEqualTo(settled);
        } finally {
            retryingClient.close();
        }
        System.clearProperty("artemis.client.operation.retryIntervalMs");
    }

    private static org.mydotey.scf.facade.StringProperties systemProperties() {
        java.util.Map<Integer, org.mydotey.scf.ConfigurationSource> sources = new java.util.LinkedHashMap<>();
        sources.put(3, org.mydotey.scf.facade.SimpleConfigurationSources.newSystemPropertiesSource());
        return new org.mydotey.scf.facade.StringProperties(
                org.mydotey.scf.facade.ConfigurationManagers.newManager("test-meters", sources));
    }

    @Test
    void should_notThrow_when_serverUnavailable() {
        client.start();
        server.rejectAll = true;

        // 服务不可达：操作吞掉失败（内部 WARN + metrics + 补投），用户无感
        assertThatCode(() -> client.register(instance("i-1"))).doesNotThrowAnyException();
        assertThatCode(() -> client.update(instance("i-1"))).doesNotThrowAnyException();
    }

    @Test
    void should_removeFromLocalSet_when_deregister() {
        client.start();
        client.register(instance("i-1"));
        client.deregister(
                InstanceId.newBuilder().service(ORDER).instanceId("i-1").build());

        TestAwait.await("deregister reported", 3000, () -> !server.deregisterRequests.isEmpty());
        assertThat(server.deregisterRequests.get(0).getInstances(0).getInstanceId())
                .isEqualTo("i-1");
        TestAwait.await(
                "deregister settles into heartbeats",
                3000,
                () -> !server.heartbeatRequests.isEmpty()
                        && !containsInstance(server.heartbeatRequests.get(server.heartbeatRequests.size() - 1), "i-1"));
    }

    @Test
    void should_reportUpdate_when_update() {
        client.start();
        client.register(instance("i-1", "10.0.0.1"));
        client.update(instance("i-1", "10.0.0.9"));

        TestAwait.await("update reported", 3000, () -> !server.updateRequests.isEmpty());
        assertThat(server.updateRequests.get(0).getServices(0).getInstances(0).getHost())
                .isEqualTo("10.0.0.9");
        TestAwait.await(
                "update settles into heartbeats",
                3000,
                () -> server.heartbeatRequests.stream()
                        .anyMatch(r -> containsInstance(r, "i-1")
                                && instanceHost(r, "i-1").equals("10.0.0.9")));
    }

    @Test
    void should_rejectOperation_when_notStarted() {
        assertThatThrownBy(() -> client.register(instance("i-1"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.update(instance("i-1"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.deregister(
                        InstanceId.newBuilder().service(ORDER).instanceId("i-1").build()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void should_sendBasicAuthHeader_when_credentialsConfigured() {
        System.setProperty("artemis.client.auth.username", "artemis");
        System.setProperty("artemis.client.auth.password", "secret");
        ArtemisClient authenticated = newClient(Metrics.globalRegistry, fixedFetcher(server.address()));
        try {
            authenticated.start();
            authenticated.register(instance("i-1"));

            TestAwait.await("auth header sent", 3000, () -> !server.authorizationHeaders.isEmpty());
            assertThat(server.authorizationHeaders.get(0)).startsWith("Basic ");
        } finally {
            authenticated.close();
            System.clearProperty("artemis.client.auth.username");
            System.clearProperty("artemis.client.auth.password");
        }
    }

    @Test
    void should_notSendAuthHeader_when_credentialsAbsent() {
        client.start(); // 凭证未配置（默认空）
        client.register(instance("i-1"));

        // 请求已到达但无认证头：裁决归服务端
        TestAwait.await("request arrives", 3000, () -> !server.registerRequests.isEmpty());
        assertThat(server.authorizationHeaders).isEmpty();
    }

    @Test
    void should_stopHeartbeatAndDeregisterAll_when_close() throws Exception {
        client.start();
        client.register(instance("i-1"));
        client.register(instance("i-2"));
        TestAwait.await(
                "instances registered via heartbeat",
                3000,
                () -> server.heartbeatRequests.stream()
                        .anyMatch(r -> containsInstance(r, "i-1") && containsInstance(r, "i-2")));

        client.close();
        client.close(); // 幂等

        TestAwait.await(
                "graceful deregister reported",
                3000,
                () -> !server.deregisterRequests.isEmpty()
                        && server.deregisterRequests.get(0).getInstancesCount() == 2);
        int heartbeatsAtClose = server.heartbeatRequests.size();
        TimeUnit.MILLISECONDS.sleep(700); // > 心跳间隔 500ms，此后不应再有心跳
        assertThat(server.heartbeatRequests.size()).isEqualTo(heartbeatsAtClose);
        assertThatThrownBy(() -> client.register(instance("i-3"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void should_failoverToOtherNode_when_currentNodeDies() throws Exception {
        FakeRegistryServer backup = new FakeRegistryServer();
        backup.start();
        ArtemisClient failoverClient = null;
        try {
            failoverClient = newClient(Metrics.globalRegistry, fixedFetcher(server.address(), backup.address()));
            failoverClient.start();
            failoverClient.register(instance("i-1"));

            // 先等任一节点收到心跳（列表就绪 + 开流），再定格当前连接节点并杀掉
            TestAwait.await(
                    "connected via heartbeat",
                    8000,
                    () -> !server.heartbeatRequests.isEmpty() || !backup.heartbeatRequests.isEmpty());
            final FakeRegistryServer connected = server.heartbeatRequests.isEmpty() ? backup : server;
            final FakeRegistryServer survivor = connected == server ? backup : server;
            int connectedCount = connected.heartbeatRequests.size();
            TestAwait.await("connection stable", 3000, () -> connected.heartbeatRequests.size() > connectedCount);
            connected.stop();

            // 无感换址：心跳落到存活节点并携带实例（杀节点后 select 排除断址 + 熔断兜底）
            TestAwait.await(
                    "heartbeat fails over to survivor with instance",
                    8000,
                    () -> survivor.heartbeatRequests.stream().anyMatch(r -> containsInstance(r, "i-1")));
        } finally {
            if (failoverClient != null) {
                failoverClient.close();
            }
            backup.stop();
        }
    }

    @Test
    void should_becomeAvailable_when_bootstrapPending() throws Exception {
        // 冷启动寻址未就绪（列表不可得）：操作不抛、心跳退避自愈；列表就绪后自动恢复上报
        final java.util.concurrent.atomic.AtomicReference<List<NodeEndpoint>> available =
                new java.util.concurrent.atomic.AtomicReference<>(new ArrayList<NodeEndpoint>());
        ArtemisClient pendingClient = newClient(Metrics.globalRegistry, new NodeListFetcher() {
            @Override
            public List<NodeEndpoint> fetch() {
                List<NodeEndpoint> current = available.get();
                if (current.isEmpty()) {
                    throw new IllegalStateException("bootstrap not ready");
                }
                return current;
            }
        });
        try {
            pendingClient.start();
            pendingClient.register(instance("i-1"));
            assertThatCode(() -> pendingClient.register(instance("i-2"))).doesNotThrowAnyException();

            Thread.sleep(300); // 退避中保持无列表（寻址失败、无选址）
            assertThat(server.heartbeatRequests).isEmpty();

            available.set(endpoints(server.address())); // 寻址成功
            TestAwait.await(
                    "heartbeat recovers after bootstrap",
                    5000,
                    () -> server.heartbeatRequests.stream().anyMatch(r -> containsInstance(r, "i-1")));
        } finally {
            pendingClient.close();
        }
    }

    @Test
    void should_failoverToReadyNode_when_currentNodeRefuses() throws Exception {
        // 节点活但开流即拒（UNAVAILABLE，服务端未就绪语义）：连续失败熔断 + 立即重寻址，换址收敛到就绪节点
        FakeRegistryServer refusing = new FakeRegistryServer();
        refusing.rejectHeartbeatOpen = true;
        refusing.start();
        FakeRegistryServer ready = new FakeRegistryServer();
        ready.start();
        final java.util.concurrent.atomic.AtomicInteger fetchCount = new java.util.concurrent.atomic.AtomicInteger();
        ArtemisClient failoverClient = null;
        try {
            failoverClient = newClient(Metrics.globalRegistry, new NodeListFetcher() {
                @Override
                public List<NodeEndpoint> fetch() {
                    // 首轮只见拒绝节点（强制起点），其后寻址刷新（熔断触发 refreshNow）扩为双节点
                    return fetchCount.incrementAndGet() == 1
                            ? endpoints(refusing.address())
                            : endpoints(refusing.address(), ready.address());
                }
            });
            failoverClient.start();
            failoverClient.register(instance("i-1"));

            TestAwait.await(
                    "heartbeat converges to ready node with instance",
                    8000,
                    () -> ready.heartbeatRequests.stream().anyMatch(r -> containsInstance(r, "i-1")));
            assertThat(refusing.heartbeatRequests).isEmpty(); // 拒绝节点从未成功开流
        } finally {
            if (failoverClient != null) {
                failoverClient.close();
            }
            refusing.stop();
            ready.stop();
        }
    }

    private static boolean containsInstance(HeartbeatRequest request, String instanceId) {
        for (ServiceInstances services : request.getServicesList()) {
            for (Instance instance : services.getInstancesList()) {
                if (instance.getInstanceId().equals(instanceId)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String instanceHost(HeartbeatRequest request, String instanceId) {
        for (ServiceInstances services : request.getServicesList()) {
            for (Instance instance : services.getInstancesList()) {
                if (instance.getInstanceId().equals(instanceId)) {
                    return instance.getHost();
                }
            }
        }
        return null;
    }
}
