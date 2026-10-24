package org.mydotey.ai.artemis.dist;

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
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.Instance;
import org.mydotey.ai.artemis.proto.common.v1.ServiceInstances;
import org.mydotey.ai.artemis.proto.common.v1.ServiceKey;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryQueryServiceGrpc;
import org.mydotey.ai.artemis.proto.discovery.v1.QueryRequest;
import org.mydotey.ai.artemis.proto.discovery.v1.QueryResponse;
import org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest;
import org.mydotey.ai.artemis.proto.registry.v1.RegisterResponse;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;

/**
 * all-in-one 端到端：同 JVM 起真实双上下文（各自随机端口、真实 gRPC），registry 外部注册 →
 * discovery 查询可见（证明 discovery 经网络消费同进程 registry，非进程内短路）。
 * registry 默认认证开启，外部调用带默认 artemis/artemis Basic 头。
 */
class AllInOneIT {

    private static final ServiceKey KEY = ServiceKey.newBuilder()
            .setNamespace("default")
            .setService("order-service")
            .build();

    private ArtemisAllInOneServer.Node node;

    private ManagedChannel registryChannel;

    private ManagedChannel discoveryChannel;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // 隔离 registry 快照（默认相对路径 data/registry-snapshot.bin 会跨运行残留、下次冷启动回放
        // 旧实例造成假绿）：每用例独立临时文件，经 registry 上下文 SCF 系统属性 source 生效
        System.setProperty(
                "artemis.registry.snapshot.path",
                tempDir.resolve("registry-snapshot.bin").toString());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("artemis.registry.snapshot.path");
        closeChannels();
        if (node != null) {
            node.close();
        }
    }

    @Test
    void should_start_both_services_on_distinct_ports() throws Exception {
        node = ArtemisAllInOneServer.start(randomOptions());
        assertThat(node.registryGrpcPort()).isPositive();
        assertThat(node.registryHttpPort()).isPositive();
        assertThat(node.discoveryGrpcPort()).isPositive();
        assertThat(node.discoveryHttpPort()).isPositive();
        // 两 gRPC 与两 HTTP 端口互不相同（各上下文独立注入，不串口）
        assertThat(List.of(
                        node.registryGrpcPort(),
                        node.registryHttpPort(),
                        node.discoveryGrpcPort(),
                        node.discoveryHttpPort()))
                .doesNotHaveDuplicates();
    }

    @Test
    void should_serve_registered_instance_through_discovery() throws Exception {
        node = ArtemisAllInOneServer.start(randomOptions());

        registryChannel = ManagedChannelBuilder.forAddress("127.0.0.1", node.registryGrpcPort())
                .usePlaintext()
                .build();
        RegistryServiceGrpc.RegistryServiceBlockingStub registryStub = RegistryServiceGrpc.newBlockingStub(
                        registryChannel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(basicAuth("artemis", "artemis")));
        discoveryChannel = ManagedChannelBuilder.forAddress("127.0.0.1", node.discoveryGrpcPort())
                .usePlaintext()
                .build();
        DiscoveryQueryServiceGrpc.DiscoveryQueryServiceBlockingStub queryStub =
                DiscoveryQueryServiceGrpc.newBlockingStub(discoveryChannel);

        awaitUntil("discovery ready", () -> statusOf(() -> queryAll(queryStub)) == null);
        register(registryStub, "i1");
        awaitUntil("instance visible", () -> instance(queryStub, "i1") != null);
    }

    @Test
    void should_stop_both_contexts_when_closed() throws Exception {
        node = ArtemisAllInOneServer.start(randomOptions());
        node.close();
        assertThat(node.registry().isActive()).isFalse();
        assertThat(node.discovery().isActive()).isFalse();
        // 重复 close 幂等
        node.close();
    }

    // ---- 起停与驱动 ----

    private static AllInOneOptions randomOptions() throws IOException {
        // peer 端口产品语义不接受 0 随机，预分配；其余端口随机缩小并行冲突面
        return AllInOneOptions.builder()
                .registryGrpcPort(0)
                .registryHttpPort(0)
                .registryManagementPort(0)
                .registryPeerPort(freePort())
                .discoveryGrpcPort(0)
                .discoveryHttpPort(0)
                .discoveryManagementPort(0)
                .build();
    }

    private static void register(RegistryServiceGrpc.RegistryServiceBlockingStub registryStub, String instanceId) {
        RegisterResponse response = registryStub.register(RegisterRequest.newBuilder()
                .addServices(ServiceInstances.newBuilder()
                        .setKey(KEY)
                        .addInstances(Instance.newBuilder()
                                .setInstanceId(instanceId)
                                .setHost("10.0.0.1")
                                .setPort(8080))
                        .build())
                .build());
        assertThat(response.getStatus().getAck()).isEqualTo(Ack.ACK_SUCCESS);
    }

    private static QueryResponse queryAll(DiscoveryQueryServiceGrpc.DiscoveryQueryServiceBlockingStub queryStub) {
        return queryStub.query(QueryRequest.newBuilder().addServices(KEY).build());
    }

    private static Instance instance(
            DiscoveryQueryServiceGrpc.DiscoveryQueryServiceBlockingStub queryStub, String instanceId) {
        QueryResponse response = queryAll(queryStub);
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

    private static Metadata basicAuth(String username, String password) {
        Metadata headers = new Metadata();
        headers.put(
                Metadata.Key.of(BasicAuth.HEADER, Metadata.ASCII_STRING_MARSHALLER),
                BasicAuth.headerValue(username, password));
        return headers;
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
        if (registryChannel != null) {
            registryChannel.shutdown();
            registryChannel = null;
        }
        if (discoveryChannel != null) {
            discoveryChannel.shutdown();
            discoveryChannel = null;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(null);
            return socket.getLocalPort();
        }
    }
}
