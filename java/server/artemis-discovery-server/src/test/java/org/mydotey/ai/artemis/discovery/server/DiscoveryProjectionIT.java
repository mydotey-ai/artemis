package org.mydotey.ai.artemis.discovery.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.Instance;
import org.mydotey.ai.artemis.proto.common.v1.ServiceKey;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryQueryServiceGrpc;
import org.mydotey.ai.artemis.proto.discovery.v1.QueryRequest;
import org.mydotey.ai.artemis.proto.discovery.v1.QueryResponse;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;
import org.mydotey.ai.artemis.registry.server.ArtemisRegistryServer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;

/**
 * 端到端投影闭环：进程内真实 registry（外部 + 内部 peer 端口）+ 真实 discovery（上游
 * 消费 projection 流、查询出口随机端口），discovery.v1 stub 直连驱动。
 * 「亚秒可见」在稳态（首轮 SYNCED 完成）后测量注册→查询可见延迟。
 */
class DiscoveryProjectionIT {

    private static final String NAMESPACE = "default";
    private static final String SERVICE = "order-service";
    private static final org.mydotey.ai.artemis.proto.common.v1.ServiceKey KEY =
            ServiceKey.newBuilder().setNamespace(NAMESPACE).setService(SERVICE).build();

    @TempDir
    Path tempDir;

    private ConfigurableApplicationContext registryContext;

    /** 每次重启换快照文件：重启用例重启即冷启动（不回放上次落盘，快照回放语义归 registry IT）。 */
    private int registrySnapshotGeneration;

    private ConfigurableApplicationContext discoveryContext;
    private ManagedChannel registryChannel;
    private ManagedChannel discoveryChannel;
    private RegistryServiceGrpc.RegistryServiceBlockingStub registryStub;
    private DiscoveryQueryServiceGrpc.DiscoveryQueryServiceBlockingStub queryStub;

    @BeforeEach
    void setUp() {
        // 端口预分配（产品内部端口不接受 0）；释放即起服务，缩小 TOCTOU 窗口
    }

    @AfterEach
    void tearDown() {
        closeChannels();
        if (discoveryContext != null) {
            discoveryContext.close();
        }
        if (registryContext != null) {
            registryContext.close();
        }
    }

    @Test
    void should_serve_registry_writes_sub_second() throws Exception {
        int registryPort = freePort();
        int peerPort = freePort();
        startRegistry(registryPort, peerPort);
        startDiscovery(peerPort);

        awaitDiscoveryReady(); // 首轮 SYNCED（此刻空表）

        long startAt = System.nanoTime();
        register("i1");
        List<String> visible = awaitInstance("i1");
        long elapsedMs = (System.nanoTime() - startAt) / 1_000_000;
        assertThat(visible).containsExactly("i1");
        assertThat(elapsedMs).as("稳态注册→查询可见应亚秒").isLessThan(1_000);
    }

    @Test
    void should_reflect_update_and_delete_on_query() throws Exception {
        int registryPort = freePort();
        int peerPort = freePort();
        startRegistry(registryPort, peerPort);
        startDiscovery(peerPort);
        awaitDiscoveryReady();

        register("i1");
        awaitInstance("i1");

        // update 改端口/状态 → 投影反映
        update("i1", 9090);
        awaitUntil("update visible", () -> {
            Instance projected = instance("i1");
            return projected != null && projected.getPort() == 9090;
        });

        // deregister → 条目删除（空即删）
        deregister("i1");
        awaitUntil("delete visible", () -> instance("i1") == null);
        assertThat(queryAll().getData().getServicesCount()).isZero();
    }

    @Test
    void should_rebootstrap_and_cover_stale_after_registry_restart() throws Exception {
        int registryPort = freePort();
        int peerPort = freePort();
        startRegistry(registryPort, peerPort);
        startDiscovery(peerPort);
        awaitDiscoveryReady();

        register("i1");
        awaitInstance("i1");

        // registry 重启（空表，快照目录独立不残留）；discovery channel 不受影响
        closeRegistryChannel();
        registryContext.close();
        registryContext = null;
        startRegistry(registryPort, peerPort);

        // discovery 断流重连 re-bootstrap：旧实例被空表覆盖清除
        awaitUntil("stale covered", () -> instance("i1") == null);
        register("i9");
        assertThat(awaitInstance("i9")).containsExactly("i9");
    }

    @Test
    void should_reject_query_with_unavailable_before_first_sync() throws Exception {
        int registryPort = freePort();
        int peerPort = freePort();
        // discovery 先起，上游无人监听
        startDiscovery(peerPort);
        assertThat(statusOf(this::queryAll)).isEqualTo(Status.Code.UNAVAILABLE);

        startRegistry(registryPort, peerPort);
        awaitDiscoveryReady();
        register("i1");
        assertThat(awaitInstance("i1")).containsExactly("i1");
    }

    // ---- 起停与驱动 ----

    private void startRegistry(int externalPort, int peerPort) {
        registrySnapshotGeneration++;
        registryContext = new SpringApplicationBuilder(ArtemisRegistryServer.class)
                .run(registryArgs(externalPort, peerPort).toArray(String[]::new));
        registryChannel = ManagedChannelBuilder.forAddress("127.0.0.1", externalPort)
                .usePlaintext()
                .build();
        registryStub = RegistryServiceGrpc.newBlockingStub(registryChannel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(basicAuth("artemis", "artemis")));
    }

    private void startDiscovery(int peerPort) {
        discoveryContext = new SpringApplicationBuilder(ArtemisDiscoveryServer.class)
                .run(discoveryArgs(peerPort).toArray(String[]::new));
        discoveryChannel = ManagedChannelBuilder.forAddress("127.0.0.1", discoveryPort())
                .usePlaintext()
                .build();
        queryStub = DiscoveryQueryServiceGrpc.newBlockingStub(discoveryChannel);
    }

    private List<String> registryArgs(int externalPort, int peerPort) {
        // spring.config.location 指向不存在位置：隔离 discovery 模块 test classpath 上的
        // application.properties（readiness group 等 discovery 专属键不进 registry context）
        List<String> args = new ArrayList<>(List.of(
                "--spring.config.location=optional:classpath:/discovery-it-registry.properties",
                "--spring.application.name=artemis-registry",
                "--spring.main.web-application-type=none",
                "--spring.grpc.server.shutdown.grace-period=1s",
                "--management.server.port=0",
                "--management.tracing.export.otlp.enabled=false",
                "--management.otlp.metrics.export.enabled=false",
                "--spring.grpc.server.port=" + externalPort,
                "--artemis.registry.internal.port=" + peerPort,
                "--artemis.registry.internal.address=127.0.0.1",
                "--artemis.registry.snapshot.path="
                        + tempDir.resolve("registry-snapshot-" + registrySnapshotGeneration + ".bin"),
                "--artemis.registry.lease.ttlMs=600000",
                "--artemis.registry.shutdown.drainMs=0"));
        return args;
    }

    private List<String> discoveryArgs(int peerPort) {
        return List.of(
                "--spring.application.name=artemis-discovery",
                "--spring.main.web-application-type=none",
                "--management.server.port=0",
                "--spring.grpc.server.port=0",
                "--artemis.discovery.registry.clusters={\"test-cluster\":\"127.0.0.1:" + peerPort + "\"}",
                "--artemis.discovery.upstream.reconnectBackoffMinMs=100",
                "--artemis.discovery.upstream.reconnectBackoffMaxMs=200");
    }

    private int discoveryPort() {
        Map<String, GrpcServerLifecycle> lifecycles = discoveryContext.getBeansOfType(GrpcServerLifecycle.class);
        return lifecycles.values().iterator().next().getPort();
    }

    private static Metadata basicAuth(String username, String password) {
        Metadata headers = new Metadata();
        headers.put(
                Metadata.Key.of(BasicAuth.HEADER, Metadata.ASCII_STRING_MARSHALLER),
                BasicAuth.headerValue(username, password));
        return headers;
    }

    private void register(String instanceId) {
        org.mydotey.ai.artemis.proto.registry.v1.RegisterResponse response =
                registryStub.register(org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest.newBuilder()
                        .addServices(serviceInstances(instanceId, 8080))
                        .build());
        assertThat(response.getStatus().getAck()).isEqualTo(Ack.ACK_SUCCESS);
    }

    private void update(String instanceId, int port) {
        org.mydotey.ai.artemis.proto.registry.v1.UpdateResponse response =
                registryStub.update(org.mydotey.ai.artemis.proto.registry.v1.UpdateRequest.newBuilder()
                        .addServices(serviceInstances(instanceId, port))
                        .build());
        assertThat(response.getStatus().getAck()).isEqualTo(Ack.ACK_SUCCESS);
    }

    private static org.mydotey.ai.artemis.proto.common.v1.ServiceInstances serviceInstances(
            String instanceId, int port) {
        return org.mydotey.ai.artemis.proto.common.v1.ServiceInstances.newBuilder()
                .setKey(KEY)
                .addInstances(Instance.newBuilder()
                        .setInstanceId(instanceId)
                        .setHost("10.0.0.1")
                        .setPort(port))
                .build();
    }

    private void deregister(String instanceId) {
        org.mydotey.ai.artemis.proto.registry.v1.DeregisterResponse response =
                registryStub.deregister(org.mydotey.ai.artemis.proto.registry.v1.DeregisterRequest.newBuilder()
                        .addInstances(org.mydotey.ai.artemis.proto.common.v1.InstanceKey.newBuilder()
                                .setService(KEY)
                                .setInstanceId(instanceId))
                        .build());
        assertThat(response.getStatus().getAck()).isEqualTo(Ack.ACK_SUCCESS);
    }

    private QueryResponse queryAll() {
        return queryStub.query(QueryRequest.newBuilder().addServices(KEY).build());
    }

    private Instance instance(String instanceId) {
        QueryResponse response = queryAll();
        if (response.getData().getServicesCount() == 0) {
            return null;
        }
        for (Instance instance :
                response.getData().getServices(0).getInstances().getInstancesList()) {
            if (instance.getInstanceId().equals(instanceId)) {
                return instance;
            }
        }
        return null;
    }

    private List<String> awaitInstance(String instanceId) {
        List<String> ids = new ArrayList<>();
        awaitUntil("instance visible: " + instanceId, () -> {
            Instance found = instance(instanceId);
            if (found == null) {
                return false;
            }
            ids.add(found.getInstanceId());
            return true;
        });
        return ids;
    }

    /** discovery ready = 查询非 UNAVAILABLE（首轮 SYNCED 前 readiness 拦截 UNAVAILABLE）。 */
    private void awaitDiscoveryReady() {
        awaitUntil("discovery ready", () -> statusOf(this::queryAll) == null);
    }

    private static Status.Code statusOf(java.util.function.Supplier<?> call) {
        try {
            call.get();
            return null;
        } catch (StatusRuntimeException e) {
            return e.getStatus().getCode();
        }
    }

    private static void awaitUntil(String what, java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("timeout waiting: " + what);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private void closeChannels() {
        closeRegistryChannel();
        if (discoveryChannel != null) {
            discoveryChannel.shutdown();
            discoveryChannel = null;
        }
    }

    private void closeRegistryChannel() {
        if (registryChannel != null) {
            registryChannel.shutdown();
            registryChannel = null;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(null);
            return socket.getLocalPort();
        }
    }
}
