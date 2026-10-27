package org.mydotey.ai.artemis.discovery.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.MetadataUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.management.server.ArtemisManagementServer;
import org.mydotey.ai.artemis.proto.common.v1.ServiceKey;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryQueryServiceGrpc;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoverySubscriptionServiceGrpc;
import org.mydotey.ai.artemis.proto.discovery.v1.QueryRequest;
import org.mydotey.ai.artemis.proto.discovery.v1.SubscribeRequest;
import org.mydotey.ai.artemis.proto.discovery.v1.SubscriptionEvent;
import org.mydotey.ai.artemis.registry.server.ArtemisRegistryServer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * console 订阅关系组合端到端 IT（v0.5 phase-5）：三服务同 JVM（registry + management +
 * discovery，DiscoveryCanaryIT 形态），discovery HTTP 端口预选注入 management
 * （endpoints 静态配置），坏源 127.0.0.1:1 常驻验证 per-source fail-open。
 * 落本模块因 Maven 依赖方向（discovery-server → management-server 既有，反向 test 依赖成环）。
 */
class DiscoveryConsoleSubscriptionsIT {

    private static final String CLUSTER = "test-cluster";
    private static final ServiceKey KEY = ServiceKey.newBuilder()
            .setNamespace("default")
            .setService("order-service")
            .build();
    private static final String MANAGEMENT_AUTH = BasicAuth.headerValue("artemis", "artemis");
    private static final String DISCOVERY_AUTH = BasicAuth.headerValue("artemis", "artemis");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path tempDir;

    private ConfigurableApplicationContext registryContext;
    private ConfigurableApplicationContext managementContext;
    private ConfigurableApplicationContext discoveryContext;
    private ManagedChannel registryChannel;
    private ManagedChannel discoveryChannel;
    private DiscoveryQueryServiceGrpc.DiscoveryQueryServiceBlockingStub queryStub;
    private DiscoverySubscriptionServiceGrpc.DiscoverySubscriptionServiceStub subscribeStub;
    private ObjectMapper objectMapper;
    private int managementHttpPort;
    private int discoveryHttpPort;

    @AfterEach
    void tearDown() {
        if (discoveryChannel != null) {
            discoveryChannel.shutdownNow();
        }
        if (registryChannel != null) {
            registryChannel.shutdownNow();
        }
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
    void should_expose_live_subscription_and_isolate_bad_source_when_composite_queried() throws Exception {
        startAll();
        CountDownLatch established = new CountDownLatch(1);
        AtomicReference<ClientCallStreamObserver<SubscribeRequest>> stream = new AtomicReference<>();
        subscribe(established, stream);
        assertThat(established.await(5, TimeUnit.SECONDS)).isTrue(); // 首条空批 = 建立确认

        JsonNode sources = json(get("/admin/v1/subscriptions")).get("sources");
        assertThat(sources.size()).isEqualTo(2);

        // 坏源（127.0.0.1:1）：error 承载、订阅空，不拖垮视图（整体 200 已由 json() 断言）
        JsonNode bad = sources.get(0);
        assertThat(bad.get("endpoint").asText()).isEqualTo("127.0.0.1:1");
        assertThat(bad.get("error").asText()).isNotEmpty();
        assertThat(bad.get("subscriptions").size()).isZero();

        // 好源：真实订阅可见（subscriber = 传输层地址、selector 拍平字段）
        JsonNode good = sources.get(1);
        assertThat(good.get("endpoint").asText()).isEqualTo("127.0.0.1:" + discoveryHttpPort);
        assertThat(good.hasNonNull("error")).isFalse();
        assertThat(good.get("subscriptions").size()).isEqualTo(1);
        JsonNode subscription = good.get("subscriptions").get(0);
        assertThat(subscription.get("subscriber").asText()).contains("127.0.0.1");
        assertThat(subscription.get("establishedAt").asText()).isNotEmpty();
        assertThat(subscription.get("services").get(0).get("namespace").asText())
                .isEqualTo("default");
        assertThat(subscription.get("labels").get("group").asText()).isEqualTo("a");
        assertThat(subscription.get("clusters").get(0).asText()).isEqualTo(CLUSTER);

        stream.get().cancel("test done", null);
    }

    @Test
    void should_remove_subscription_from_composite_when_stream_closed() throws Exception {
        startAll();
        CountDownLatch established = new CountDownLatch(1);
        AtomicReference<ClientCallStreamObserver<SubscribeRequest>> stream = new AtomicReference<>();
        subscribe(established, stream);
        assertThat(established.await(5, TimeUnit.SECONDS)).isTrue();
        awaitUntil("subscription visible in composite", () -> liveSubscriptions() == 1);

        stream.get().cancel("test done", null);
        awaitUntil("subscription removed from composite", () -> liveSubscriptions() == 0);
    }

    // ---- 订阅驱动 ----

    /** 建立真实订阅流：established 首条空批时 countDown，流引用落 stream（供取消）。 */
    private void subscribe(
            CountDownLatch established, AtomicReference<ClientCallStreamObserver<SubscribeRequest>> stream) {
        ClientResponseObserver<SubscribeRequest, SubscriptionEvent> observer = new ClientResponseObserver<>() {

            @Override
            public void beforeStart(ClientCallStreamObserver<SubscribeRequest> requestStream) {
                stream.set(requestStream);
            }

            @Override
            public void onNext(SubscriptionEvent event) {
                established.countDown(); // 首条空批 = 建立确认
            }

            @Override
            public void onError(Throwable error) {}

            @Override
            public void onCompleted() {}
        };
        subscribeStub.subscribe(
                SubscribeRequest.newBuilder()
                        .addServices(KEY)
                        .putLabels("group", "a")
                        .addClusters(CLUSTER)
                        .build(),
                observer);
    }

    private int liveSubscriptions() {
        try {
            JsonNode sources = json(get("/admin/v1/subscriptions")).get("sources");
            int count = 0;
            for (JsonNode source : sources) {
                count += source.get("subscriptions").size();
            }
            return count;
        } catch (Exception e) {
            return -1; // 轮询期间管理面未起完（启动竞态），继续等
        }
    }

    // ---- 服务起停（DiscoveryCanaryIT 形态 + discovery HTTP 预选注入）----

    private void startAll() throws IOException {
        int registryExternalPort = freePort();
        int registryPeerPort = freePort();
        int managementGrpcPort = freePortUnchecked();
        discoveryHttpPort = freePortUnchecked(); // management endpoints 静态注入，端口须先知
        startRegistry(registryExternalPort, registryPeerPort);
        startManagement(managementGrpcPort);
        startDiscovery(registryPeerPort, managementGrpcPort);
        awaitUntil("discovery ready", () -> {
            try {
                queryStub.query(QueryRequest.newBuilder().addServices(KEY).build());
                return true;
            } catch (Exception e) {
                return false;
            }
        });
    }

    private void startRegistry(int externalPort, int peerPort) {
        registryContext = new SpringApplicationBuilder(ArtemisRegistryServer.class)
                .run(
                        // 同 DiscoveryCanaryIT：optional 指向不存在文件 = 阻断测试 classpath 上
                        // discovery/management 同名 application.properties 误载（readiness 组键冲突）
                        "--spring.config.location=optional:classpath:/console-it-registry.properties",
                        "--spring.application.name=artemis-registry",
                        "--spring.main.web-application-type=none",
                        "--spring.grpc.server.shutdown.grace-period=1s",
                        "--management.server.port=0",
                        "--management.tracing.export.otlp.enabled=false",
                        "--management.otlp.metrics.export.enabled=false",
                        "--spring.grpc.server.port=" + externalPort,
                        "--artemis.registry.internal.port=" + peerPort,
                        "--artemis.registry.internal.address=127.0.0.1",
                        "--artemis.registry.snapshot.path=" + tempDir.resolve("registry-snapshot.bin"),
                        "--artemis.registry.lease.ttlMs=600000",
                        "--artemis.registry.shutdown.drainMs=0");
        registryChannel = ManagedChannelBuilder.forAddress("127.0.0.1", externalPort)
                .usePlaintext()
                .build();
    }

    private void startManagement(int managementGrpcPort) {
        managementContext = new SpringApplicationBuilder(ArtemisManagementServer.class)
                .run(
                        "--spring.config.location=optional:classpath:/console-it-management.properties",
                        "--spring.application.name=artemis-management",
                        "--server.port=0",
                        "--management.server.port=0",
                        "--management.tracing.export.otlp.enabled=false",
                        "--management.otlp.metrics.export.enabled=false",
                        "--spring.grpc.server.port=" + managementGrpcPort,
                        "--artemis.management.grpc.port=" + managementGrpcPort,
                        "--artemis.management.registry.endpoint=127.0.0.1:1", // 惰性，不触达
                        // 组合源：常驻坏源验证 per-source fail-open + 真实 discovery 源
                        "--artemis.management.discovery.endpoints=127.0.0.1:1,127.0.0.1:" + discoveryHttpPort,
                        "--artemis.management.discovery.timeoutMs=2000");
        managementHttpPort = managementContext.getEnvironment().getProperty("local.server.port", Integer.class);
        objectMapper = managementContext.getBean(ObjectMapper.class);
    }

    private void startDiscovery(int peerPort, int managementGrpcPort) {
        discoveryContext = new SpringApplicationBuilder(ArtemisDiscoveryServer.class)
                .run(
                        "--spring.application.name=artemis-discovery",
                        "--server.port=" + discoveryHttpPort,
                        "--management.server.port=0",
                        "--spring.grpc.server.shutdown.grace-period=1s",
                        "--spring.grpc.server.port=0",
                        "--artemis.discovery.registry.clusters={\"" + CLUSTER + "\":\"127.0.0.1:" + peerPort + "\"}",
                        "--artemis.discovery.management.endpoints=127.0.0.1:" + managementGrpcPort,
                        "--artemis.discovery.management.reconnectBackoffMinMs=200",
                        "--artemis.discovery.management.reconnectBackoffMaxMs=1000",
                        "--artemis.discovery.upstream.reconnectBackoffMinMs=100",
                        "--artemis.discovery.upstream.reconnectBackoffMaxMs=200",
                        // discovery /admin 认证关闭（默认）：组合端到端聚焦通道语义
                        "--management.tracing.export.otlp.enabled=false",
                        "--management.otlp.metrics.export.enabled=false");
        int discoveryGrpcPort = discoveryContext
                .getBeansOfType(GrpcServerLifecycle.class)
                .values()
                .iterator()
                .next()
                .getPort();
        discoveryChannel = ManagedChannelBuilder.forAddress("127.0.0.1", discoveryGrpcPort)
                .usePlaintext()
                .build();
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of(BasicAuth.HEADER, Metadata.ASCII_STRING_MARSHALLER), DISCOVERY_AUTH);
        var auth = MetadataUtils.newAttachHeadersInterceptor(headers);
        queryStub = DiscoveryQueryServiceGrpc.newBlockingStub(discoveryChannel).withInterceptors(auth);
        subscribeStub =
                DiscoverySubscriptionServiceGrpc.newStub(discoveryChannel).withInterceptors(auth);
    }

    // ---- HTTP 基建（management 组合出口）----

    private HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + managementHttpPort + path))
                        .header("Authorization", MANAGEMENT_AUTH)
                        .header("Accept", "application/json")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) throws IOException {
        assertThat(response.statusCode())
                .as("response body: %s", response.body())
                .isBetween(200, 299);
        return objectMapper.readTree(response.body());
    }

    private static void awaitUntil(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
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
            throw new UncheckedIOException(e);
        }
    }
}
