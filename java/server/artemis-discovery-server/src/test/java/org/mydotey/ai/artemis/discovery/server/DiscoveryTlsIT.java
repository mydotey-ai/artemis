package org.mydotey.ai.artemis.discovery.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.common.tls.GrpcTlsContexts;
import org.mydotey.ai.artemis.common.tls.TestCertificates;
import org.mydotey.ai.artemis.common.tls.TlsClientSettings;
import org.mydotey.ai.artemis.common.tls.TlsSettings;
import org.mydotey.ai.artemis.discovery.domain.RouteRule;
import org.mydotey.ai.artemis.discovery.infrastructure.RouteRuleSynchronizer;
import org.mydotey.ai.artemis.management.server.ArtemisManagementServer;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.Instance;
import org.mydotey.ai.artemis.proto.common.v1.ServiceKey;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryQueryServiceGrpc;
import org.mydotey.ai.artemis.proto.discovery.v1.QueryRequest;
import org.mydotey.ai.artemis.proto.discovery.v1.QueryResponse;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;
import org.mydotey.ai.artemis.registry.server.ArtemisRegistryServer;
import org.mydotey.scf.facade.ConfigurationManagers;
import org.mydotey.scf.facade.SimpleConfigurationSources;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.source.stringproperty.memorymap.MemoryMapConfigurationSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;

/**
 * TLS 三服务端到端（v1.0 phase-1）：registry（外部 gRPC + 内部 peer）+ discovery（外部 gRPC/HTTP +
 * 上游两路）+ management（gRPC 规则流 + console HTTP）全 TLS——注册→投影可见（#6）、规则发布生效
 * （#7）、console/服务视图 https（#9/#8）、discovery 外部 HTTP（#4）。SDK 客户端侧 e2e 归
 * registry-server RegistryTlsIT（artemis-client 测试依赖在彼）。
 */
class DiscoveryTlsIT {

    private static final String CLUSTER = "cluster-a";
    private static final String NAMESPACE = "default";
    private static final String SERVICE = "order-service";
    private static final ServiceKey KEY =
            ServiceKey.newBuilder().setNamespace(NAMESPACE).setService(SERVICE).build();
    private static final String AUTH = BasicAuth.headerValue("artemis", "artemis");

    private static TestCertificates certificates;
    private static HttpClient httpsClient;

    @TempDir
    Path tempDir;

    private ConfigurableApplicationContext registryContext;
    private ConfigurableApplicationContext managementContext;
    private ConfigurableApplicationContext discoveryContext;
    private final List<ManagedChannel> channels = new ArrayList<>();
    private int registryExternalPort;
    private int registryPeerPort;
    private int managementGrpcPort;
    private int managementHttpPort;
    private int discoveryHttpPort;
    private int snapshotGeneration;

    @BeforeAll
    static void certificatesAndHttpsClient() {
        certificates = TestCertificates.generate("discovery-tls");
        httpsClient = HttpClient.newBuilder()
                .sslContext(certificates.clientSslContext())
                .build();
    }

    @AfterAll
    static void closeCertificates() {
        certificates.close();
    }

    @AfterEach
    void tearDown() {
        for (ManagedChannel channel : channels) {
            channel.shutdownNow();
        }
        channels.clear();
        if (discoveryContext != null) {
            discoveryContext.close();
        }
        if (managementContext != null) {
            managementContext.close();
        }
        if (registryContext != null) {
            registryContext.close();
        }
    }

    @Test
    void should_serve_full_topology_over_tls() throws Exception {
        startRegistry();
        startManagement();
        startDiscovery();

        // 注册（registry 外部 gRPC TLS 直驱）→ discovery 投影消费（peer TLS #6）→ 外部查询（#3 TLS）
        registerOverTls("i-1");
        awaitUntil(
                "instance visible via discovery over TLS",
                () -> queryOverTls().getData().getServicesList().stream()
                        .flatMap(services -> services.getInstances().getInstancesList().stream())
                        .anyMatch(instance -> instance.getInstanceId().equals("i-1")));

        // 规则流（#7 discovery→management gRPC TLS）：create + release → 视图生效 + 水位对齐
        postManagement(
                "/admin/v1/route-rules",
                "{\"namespace\":\"" + NAMESPACE + "\",\"service\":\"" + SERVICE + "\",\"name\":\"beta\","
                        + "\"targets\":[{\"selector\":{\"group\":\"beta\"},\"unreleasedWeight\":20},"
                        + "{\"selector\":{},\"unreleasedWeight\":80}]}");
        postManagement("/admin/v1/route-rules/" + NAMESPACE + "/" + SERVICE + "/beta/release", "");
        RouteRuleSynchronizer synchronizer = discoveryContext.getBean(RouteRuleSynchronizer.class);
        awaitUntil("rule visible", () -> targetWeight(synchronizer, "beta", "group=beta") == 20);

        // console https（#9）+ registry 事实查询 over TLS（#8 management→registry peer）
        HttpRequest services = HttpRequest.newBuilder()
                .uri(URI.create("https://localhost:" + managementHttpPort + "/admin/v1/services"))
                .header("Authorization", AUTH)
                .GET()
                .build();
        assertThat(httpsClient
                        .send(services, HttpResponse.BodyHandlers.ofString())
                        .statusCode())
                .isEqualTo(200);

        // discovery 外部 HTTP TLS（#4 /cluster/nodes）
        HttpRequest nodes = HttpRequest.newBuilder()
                .uri(URI.create("https://localhost:" + discoveryHttpPort + "/cluster/nodes"))
                .header("Authorization", AUTH)
                .GET()
                .build();
        assertThat(httpsClient.send(nodes, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(200);
    }

    // ---- 断言辅助 ----

    private static int targetWeight(RouteRuleSynchronizer synchronizer, String name, String selectorKey) {
        return synchronizer
                .rules()
                .find(new org.mydotey.ai.artemis.discovery.domain.ServiceKey(NAMESPACE, SERVICE), name)
                .map(rule -> rule.targets().stream()
                        .filter(target -> selectorOf(target).equals(selectorKey))
                        .findFirst()
                        .map(RouteRule.RouteRuleTarget::weight)
                        .orElse(-1))
                .orElse(-1);
    }

    private static String selectorOf(RouteRule.RouteRuleTarget target) {
        return target.selector().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .sorted()
                .reduce((a, b) -> a + ";" + b)
                .orElse("");
    }

    private QueryResponse queryOverTls() {
        DiscoveryQueryServiceGrpc.DiscoveryQueryServiceBlockingStub stub =
                DiscoveryQueryServiceGrpc.newBlockingStub(tlsChannel(discoveryGrpcPort(), "discovery-tls-query"));
        return stub.query(QueryRequest.newBuilder().addServices(KEY).build());
    }

    // ---- 起停 ----

    private void startRegistry() throws IOException {
        registryExternalPort = freePort();
        registryPeerPort = freePort();
        snapshotGeneration++;
        registryContext = new SpringApplicationBuilder(ArtemisRegistryServer.class)
                .run(
                        "--spring.config.location=optional:classpath:/ack-it-registry.properties",
                        "--spring.application.name=artemis-registry",
                        "--spring.main.web-application-type=none",
                        "--spring.grpc.server.shutdown.grace-period=1s",
                        "--management.server.port=0",
                        "--management.tracing.export.otlp.enabled=false",
                        "--management.otlp.metrics.export.enabled=false",
                        "--spring.grpc.server.port=" + registryExternalPort,
                        "--spring.grpc.server.ssl.enabled=true",
                        "--spring.grpc.server.ssl.bundle=artemis",
                        "--spring.ssl.bundle.pem.artemis.keystore.certificate=" + certificates.certChainPath(),
                        "--spring.ssl.bundle.pem.artemis.keystore.private-key=" + certificates.privateKeyPath(),
                        "--artemis.registry.internal.port=" + registryPeerPort,
                        "--artemis.registry.internal.address=127.0.0.1",
                        "--artemis.registry.internal.tls.enabled=true",
                        "--artemis.registry.internal.tls.certChainPath=" + certificates.certChainPath(),
                        "--artemis.registry.internal.tls.privateKeyPath=" + certificates.privateKeyPath(),
                        "--artemis.registry.snapshot.path="
                                + tempDir.resolve("registry-snapshot-" + snapshotGeneration + ".bin"),
                        "--artemis.registry.lease.ttlMs=600000",
                        "--artemis.registry.shutdown.drainMs=0");
    }

    private void startManagement() {
        managementGrpcPort = freePortUnchecked();
        managementContext = new SpringApplicationBuilder(ArtemisManagementServer.class)
                .run(
                        "--spring.config.location=optional:classpath:/ack-it-management.properties",
                        "--spring.application.name=artemis-management",
                        "--server.port=0",
                        "--server.ssl.bundle=artemis",
                        "--spring.ssl.bundle.pem.artemis.keystore.certificate=" + certificates.certChainPath(),
                        "--spring.ssl.bundle.pem.artemis.keystore.private-key=" + certificates.privateKeyPath(),
                        "--management.server.port=0",
                        "--management.tracing.export.otlp.enabled=false",
                        "--management.otlp.metrics.export.enabled=false",
                        "--spring.grpc.server.port=" + managementGrpcPort,
                        "--spring.grpc.server.ssl.enabled=true",
                        "--spring.grpc.server.ssl.bundle=artemis",
                        "--artemis.management.grpc.port=" + managementGrpcPort,
                        "--artemis.management.registry.endpoint=127.0.0.1:" + registryPeerPort,
                        "--artemis.management.registry.tls.enabled=true",
                        "--artemis.management.registry.tls.trustCaPath=" + certificates.trustCaPath());
        managementHttpPort = managementContext.getEnvironment().getProperty("local.server.port", Integer.class);
    }

    private void startDiscovery() {
        discoveryContext = new SpringApplicationBuilder(ArtemisDiscoveryServer.class)
                .run(
                        "--spring.application.name=artemis-discovery",
                        "--server.port=0",
                        "--server.ssl.bundle=artemis",
                        "--spring.ssl.bundle.pem.artemis.keystore.certificate=" + certificates.certChainPath(),
                        "--spring.ssl.bundle.pem.artemis.keystore.private-key=" + certificates.privateKeyPath(),
                        "--management.server.port=0",
                        "--management.tracing.export.otlp.enabled=false",
                        "--management.otlp.metrics.export.enabled=false",
                        "--spring.grpc.server.port=0",
                        "--spring.grpc.server.ssl.enabled=true",
                        "--spring.grpc.server.ssl.bundle=artemis",
                        "--spring.grpc.server.shutdown.grace-period=1s",
                        "--artemis.discovery.registry.clusters={\"" + CLUSTER + "\":\"127.0.0.1:" + registryPeerPort
                                + "\"}",
                        "--artemis.discovery.registry.tls.enabled=true",
                        "--artemis.discovery.registry.tls.trustCaPath=" + certificates.trustCaPath(),
                        "--artemis.discovery.management.endpoints=127.0.0.1:" + managementGrpcPort,
                        "--artemis.discovery.management.tls.enabled=true",
                        "--artemis.discovery.management.tls.trustCaPath=" + certificates.trustCaPath(),
                        "--artemis.discovery.management.reconnectBackoffMinMs=200",
                        "--artemis.discovery.management.reconnectBackoffMaxMs=1000");
        discoveryHttpPort = discoveryContext.getEnvironment().getProperty("local.server.port", Integer.class);
    }

    // ---- 驱动 ----

    private void registerOverTls(String instanceId) {
        RegistryServiceGrpc.RegistryServiceBlockingStub stub = RegistryServiceGrpc.newBlockingStub(
                        tlsChannel(registryExternalPort, "registry-tls-register"))
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(basicAuth()));
        org.mydotey.ai.artemis.proto.registry.v1.RegisterResponse response =
                stub.register(org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest.newBuilder()
                        .addServices(org.mydotey.ai.artemis.proto.common.v1.ServiceInstances.newBuilder()
                                .setKey(KEY)
                                .addInstances(Instance.newBuilder()
                                        .setInstanceId(instanceId)
                                        .setHost("10.0.0.1")
                                        .setPort(8080))
                                .build())
                        .build());
        assertThat(response.getStatus().getAck()).isEqualTo(Ack.ACK_SUCCESS);
    }

    /** TLS channel（自定义信任锚 = 测试自签 CA），netty builder（server 模块 grpc-netty 可用）。 */
    private ManagedChannel tlsChannel(int port, String managerName) {
        MemoryMapConfigurationSource source = SimpleConfigurationSources.newMemoryMapSource();
        source.setProperties(Map.of("test.tls.enabled", "true", "test.tls.trustCaPath", certificates.trustCaPath()));
        StringProperties properties =
                new StringProperties(ConfigurationManagers.newManager(managerName, Map.of(1, source)));
        TlsClientSettings tls = TlsSettings.client(properties, "test.tls");
        ManagedChannel channel =
                GrpcTlsContexts.channelForAddress("127.0.0.1", port, tls).build();
        channels.add(channel);
        return channel;
    }

    private static Metadata basicAuth() {
        Metadata headers = new Metadata();
        headers.put(
                Metadata.Key.of(BasicAuth.HEADER, Metadata.ASCII_STRING_MARSHALLER),
                BasicAuth.headerValue("artemis", "artemis"));
        return headers;
    }

    private void postManagement(String path, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://localhost:" + managementHttpPort + path))
                    .header("Authorization", AUTH)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            httpsClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int discoveryGrpcPort() {
        Map<String, GrpcServerLifecycle> lifecycles = discoveryContext.getBeansOfType(GrpcServerLifecycle.class);
        return lifecycles.values().iterator().next().getPort();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(null);
            return socket.getLocalPort();
        }
    }

    private static int freePortUnchecked() {
        try {
            return freePort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void awaitUntil(String what, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within timeout: " + what);
            }
            Thread.sleep(50);
        }
    }
}
