package org.mydotey.ai.artemis.registry.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mydotey.ai.artemis.client.ArtemisClients;
import org.mydotey.ai.artemis.client.RegistryClient;
import org.mydotey.ai.artemis.client.ServiceId;
import org.mydotey.ai.artemis.client.ServiceInstance;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.common.tls.GrpcTlsContexts;
import org.mydotey.ai.artemis.common.tls.TestCertificates;
import org.mydotey.ai.artemis.common.tls.TlsClientSettings;
import org.mydotey.ai.artemis.common.tls.TlsSettings;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusRequest;
import org.mydotey.ai.artemis.proto.replication.v1.NodeStatusServiceGrpc;
import org.mydotey.ai.artemis.registry.application.RegistryService;
import org.mydotey.ai.artemis.registry.domain.ServiceKey;
import org.mydotey.scf.facade.ConfigurationManagers;
import org.mydotey.scf.facade.SimpleConfigurationSources;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.source.stringproperty.memorymap.MemoryMapConfigurationSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * TLS 端到端（v1.0 phase-1）：单节点 registry 外部 gRPC + 外部 HTTP + 内部 peer 端口全 TLS，
 * SDK（artemis.client.tls：https 前置寻址 + gRPC）注册闭环；双节点经 TLS peer 端口复制；
 * 明文/无信任锚客户端被拒。证书 = {@link TestCertificates}（keytool 自签，SAN localhost/127.0.0.1）。
 */
class RegistryTlsIT {

    private static final ServiceId ORDER =
            ServiceId.newBuilder().namespace("default").service("order-service").build();

    private static final ServiceKey DOMAIN_KEY = new ServiceKey("default", "order-service");

    private static final TestCertificates CERTIFICATES = TestCertificates.generate("registry-tls");

    @TempDir
    Path tempDir;

    private ConfigurableApplicationContext node1;

    private ConfigurableApplicationContext node2;

    private RegistryClient client;

    private final List<ManagedChannel> channels = new ArrayList<>();

    @BeforeAll
    static void sdkTlsSystemProperties() {
        // SDK 缺省配置源 = 系统属性 > 环境变量（ArtemisClients），TLS 与地址经系统属性注入
        System.setProperty("artemis.client.tls.enabled", "true");
        System.setProperty("artemis.client.tls.trustCaPath", CERTIFICATES.trustCaPath());
        System.setProperty("artemis.client.auth.username", "artemis");
        System.setProperty("artemis.client.auth.password", "artemis");
    }

    @AfterAll
    static void clearSystemProperties() {
        System.clearProperty("artemis.client.tls.enabled");
        System.clearProperty("artemis.client.tls.trustCaPath");
        System.clearProperty("artemis.client.auth.username");
        System.clearProperty("artemis.client.auth.password");
        System.clearProperty("artemis.client.registry.addresses");
        CERTIFICATES.close();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (client != null) {
            client.close();
            client = null;
        }
        for (ManagedChannel channel : channels) {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
        channels.clear();
        if (node2 != null) {
            node2.close();
            node2 = null;
        }
        if (node1 != null) {
            node1.close();
            node1 = null;
        }
    }

    @Test
    void should_register_over_tls_sdk_with_https_node_fetch_on_single_node() throws Exception {
        int externalPort = TestGrpcPorts.freePort();
        int peerPort = TestGrpcPorts.freePort();
        int httpPort = TestGrpcPorts.freePort();
        node1 = startNode(
                "node-0",
                externalPort,
                peerPort,
                httpPort,
                "node-0@127.0.0.1:" + peerPort + "@127.0.0.1:" + externalPort);

        // 服务端侧前置断言（二分定位 + 覆盖 #1/#2 服务端面）：可信 TLS gRPC（对外节点状态 API）
        // 与 https /cluster/nodes 均可达，否则后续 SDK 失败归因会混淆客户端与服务端
        Metadata authHeaders = new Metadata();
        authHeaders.put(
                Metadata.Key.of(BasicAuth.HEADER, Metadata.ASCII_STRING_MARSHALLER),
                BasicAuth.headerValue("artemis", "artemis"));
        NodeStatusServiceGrpc.NodeStatusServiceBlockingStub trusted = NodeStatusServiceGrpc.newBlockingStub(
                        tlsChannel(externalPort, "precheck-grpc"))
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(authHeaders))
                .withDeadlineAfter(5, TimeUnit.SECONDS);
        assertThat(trusted.getNodeStatus(GetNodeStatusRequest.getDefaultInstance())
                        .getStatus()
                        .getAck())
                .isEqualTo(Ack.ACK_SUCCESS);
        assertThat(httpsGetStatus("https://localhost:" + httpPort + "/cluster/nodes"))
                .isEqualTo(200);

        // SDK：https 前置寻址（外部 HTTP TLS）→ gRPC 注册/心跳（外部 gRPC TLS）
        System.setProperty("artemis.client.registry.addresses", "https://127.0.0.1:" + httpPort);
        client = ArtemisClients.newRegistryClient();
        client.start();
        client.register(ServiceInstance.newBuilder()
                .service(ORDER)
                .instanceId("i-1")
                .host("10.0.0.1")
                .port(8080)
                .weight(100)
                .build());

        RegistryService service = node1.getBean(RegistryService.class);
        org.mydotey.ai.artemis.registry.TestAwait.await(
                "sdk instance visible over TLS",
                () -> service.getInstances(DOMAIN_KEY)
                                .map(snapshot -> snapshot.instances().size())
                                .orElse(0)
                        == 1);
    }

    @Test
    void should_replicate_over_tls_peer_channel_between_nodes() {
        int ext1 = TestGrpcPorts.freePort();
        int peer1 = TestGrpcPorts.freePort();
        int ext2 = TestGrpcPorts.freePort();
        int peer2 = TestGrpcPorts.freePort();
        String members = "node-0@127.0.0.1:" + peer1 + "@127.0.0.1:" + ext1 + ",node-1@127.0.0.1:" + peer2
                + "@127.0.0.1:" + ext2;
        node1 = startNode("node-0", ext1, peer1, 0, members);
        node2 = startNode("node-1", ext2, peer2, 0, members);

        registerOverTls(ext1, "i-2");

        RegistryService service2 = node2.getBean(RegistryService.class);
        org.mydotey.ai.artemis.registry.TestAwait.await(
                "replicated over TLS peer channel",
                () -> service2.getInstances(DOMAIN_KEY)
                                .map(snapshot -> snapshot.instances().size())
                                .orElse(0)
                        == 1);
    }

    @Test
    void should_reject_plaintext_and_untrusted_tls_clients() {
        int externalPort = TestGrpcPorts.freePort();
        int peerPort = TestGrpcPorts.freePort();
        node1 = startNode(
                "node-0", externalPort, peerPort, 0, "node-0@127.0.0.1:" + peerPort + "@127.0.0.1:" + externalPort);

        NodeStatusServiceGrpc.NodeStatusServiceBlockingStub plaintext = NodeStatusServiceGrpc.newBlockingStub(
                        ManagedChannelBuilder.forAddress("127.0.0.1", externalPort)
                                .usePlaintext()
                                .build())
                .withDeadlineAfter(2, TimeUnit.SECONDS);
        assertThat(statusOf(() -> plaintext.getNodeStatus(GetNodeStatusRequest.getDefaultInstance())))
                .isEqualTo(Status.Code.UNAVAILABLE);

        // TLS 但默认信任库（自签证书不在其中）：握手失败 → UNAVAILABLE
        NodeStatusServiceGrpc.NodeStatusServiceBlockingStub untrusted = NodeStatusServiceGrpc.newBlockingStub(
                        NettyChannelBuilder.forAddress("127.0.0.1", externalPort)
                                .build())
                .withDeadlineAfter(2, TimeUnit.SECONDS);
        assertThat(statusOf(() -> untrusted.getNodeStatus(GetNodeStatusRequest.getDefaultInstance())))
                .isEqualTo(Status.Code.UNAVAILABLE);
    }

    // ---- 起停与驱动 ----

    private ConfigurableApplicationContext startNode(
            String nodeId, int externalPort, int peerPort, int httpPort, String members) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.application.name=artemis-registry",
                "--spring.main.web-application-type=" + (httpPort > 0 ? "servlet" : "none"),
                "--spring.grpc.server.shutdown.grace-period=1s",
                "--management.server.port=0",
                "--management.tracing.export.otlp.enabled=false",
                "--management.otlp.metrics.export.enabled=false",
                "--spring.grpc.server.port=" + externalPort,
                "--artemis.registry.internal.port=" + peerPort,
                "--artemis.registry.internal.address=127.0.0.1",
                "--artemis.registry.cluster.members=" + members,
                "--artemis.registry.cluster.self=" + nodeId,
                "--artemis.registry.snapshot.path=" + tempDir.resolve("snapshot-" + nodeId + ".bin"),
                "--artemis.registry.lease.ttlMs=600000",
                "--artemis.registry.shutdown.drainMs=0",
                // SDK 侧排障：node fetch / 上报失败细节在 DEBUG（Boot 重置全局 logback，经 app 参数开）
                "--logging.level.org.mydotey.ai.artemis.client=DEBUG",
                // 外部 gRPC + HTTP TLS：Spring SSL bundle（框架键，外部端口零代码）
                "--spring.ssl.bundle.pem.artemis.keystore.certificate=" + CERTIFICATES.certChainPath(),
                "--spring.ssl.bundle.pem.artemis.keystore.private-key=" + CERTIFICATES.privateKeyPath(),
                "--spring.grpc.server.ssl.enabled=true",
                "--spring.grpc.server.ssl.bundle=artemis",
                // 内部 peer 端口 TLS：入站证书 + 出站 peer 信任（同组键，对称）
                "--artemis.registry.internal.tls.enabled=true",
                "--artemis.registry.internal.tls.certChainPath=" + CERTIFICATES.certChainPath(),
                "--artemis.registry.internal.tls.privateKeyPath=" + CERTIFICATES.privateKeyPath(),
                "--artemis.registry.internal.tls.trustCaPath=" + CERTIFICATES.trustCaPath()));
        if (httpPort > 0) {
            args.add("--server.port=" + httpPort);
            args.add("--server.ssl.bundle=artemis");
        }
        ConfigurableApplicationContext context =
                new SpringApplicationBuilder(ArtemisRegistryServer.class).run(args.toArray(String[]::new));
        org.mydotey.ai.artemis.registry.TestAwait.await(
                nodeId + " ready",
                () -> context.getBean(org.mydotey.ai.artemis.registry.application.ReadinessGate.class)
                        .isReady());
        return context;
    }

    /** TLS channel（自定义信任锚 = 测试 CA）。 */
    private ManagedChannel tlsChannel(int port, String managerName) {
        MemoryMapConfigurationSource source = SimpleConfigurationSources.newMemoryMapSource();
        source.setProperties(Map.of("test.tls.enabled", "true", "test.tls.trustCaPath", CERTIFICATES.trustCaPath()));
        StringProperties properties =
                new StringProperties(ConfigurationManagers.newManager(managerName, Map.of(1, source)));
        TlsClientSettings tls = TlsSettings.client(properties, "test.tls");
        ManagedChannel channel =
                GrpcTlsContexts.channelForAddress("127.0.0.1", port, tls).build();
        channels.add(channel);
        return channel;
    }

    /** https GET 状态码（JDK HttpClient + 测试 CA 信任）。 */
    private static int httpsGetStatus(String url) throws Exception {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("Authorization", BasicAuth.headerValue("artemis", "artemis"))
                .GET()
                .build();
        return java.net.http.HttpClient.newBuilder()
                .sslContext(CERTIFICATES.clientSslContext())
                .build()
                .send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
                .statusCode();
    }

    /** 外部 gRPC 直驱（TLS + 自定义信任锚 + Basic 认证），断言注册 ack。 */
    private void registerOverTls(int externalPort, String instanceId) {
        ManagedChannel channel = tlsChannel(externalPort, "tls-it-" + instanceId);
        Metadata headers = new Metadata();
        headers.put(
                Metadata.Key.of(BasicAuth.HEADER, Metadata.ASCII_STRING_MARSHALLER),
                BasicAuth.headerValue("artemis", "artemis"));
        RegistryServiceGrpc.RegistryServiceBlockingStub stub = RegistryServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
        org.mydotey.ai.artemis.proto.registry.v1.RegisterResponse response =
                stub.register(org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest.newBuilder()
                        .addServices(org.mydotey.ai.artemis.proto.common.v1.ServiceInstances.newBuilder()
                                .setKey(org.mydotey.ai.artemis.proto.common.v1.ServiceKey.newBuilder()
                                        .setNamespace("default")
                                        .setService("order-service"))
                                .addInstances(org.mydotey.ai.artemis.proto.common.v1.Instance.newBuilder()
                                        .setInstanceId(instanceId)
                                        .setHost("10.0.0.1")
                                        .setPort(8080)))
                        .build());
        assertThat(response.getStatus().getAck()).isEqualTo(Ack.ACK_SUCCESS);
    }

    private static Status.Code statusOf(java.util.function.Supplier<?> call) {
        try {
            call.get();
            return Status.Code.OK;
        } catch (StatusRuntimeException e) {
            return e.getStatus().getCode();
        }
    }
}
