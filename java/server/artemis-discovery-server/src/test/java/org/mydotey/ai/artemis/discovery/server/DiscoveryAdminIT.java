package org.mydotey.ai.artemis.discovery.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.MetadataUtils;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.Instance;
import org.mydotey.ai.artemis.proto.common.v1.ServiceKey;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryQueryServiceGrpc;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoverySubscriptionServiceGrpc;
import org.mydotey.ai.artemis.proto.discovery.v1.QueryRequest;
import org.mydotey.ai.artemis.proto.discovery.v1.SubscribeRequest;
import org.mydotey.ai.artemis.proto.discovery.v1.SubscriptionEvent;
import org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;
import org.mydotey.ai.artemis.registry.server.ArtemisRegistryServer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 发现管理面端到端：进程内真实 registry + discovery（gRPC 数据面 + HTTP Admin 面），
 * 经真实 gRPC 注册/订阅与真实 HTTP + Basic 认证驱动只读 Admin API。
 */
class DiscoveryAdminIT {

    private static final String CLUSTER = "test-cluster";
    private static final String NAMESPACE = "default";
    private static final String SERVICE = "order-service";
    private static final String AUTH = BasicAuth.headerValue("artemis", "artemis");
    private static final ServiceKey KEY =
            ServiceKey.newBuilder().setNamespace(NAMESPACE).setService(SERVICE).build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path tempDir;

    private ConfigurableApplicationContext registryContext;
    private ConfigurableApplicationContext discoveryContext;
    private ManagedChannel registryChannel;
    private ManagedChannel discoveryChannel;
    private RegistryServiceGrpc.RegistryServiceBlockingStub registryStub;
    private DiscoveryQueryServiceGrpc.DiscoveryQueryServiceBlockingStub queryStub;
    private DiscoverySubscriptionServiceGrpc.DiscoverySubscriptionServiceStub subscribeStub;
    private ObjectMapper objectMapper;

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
        if (registryContext != null) {
            registryContext.close();
        }
    }

    @Test
    void should_require_auth_when_admin_api_called_without_credentials() throws Exception {
        startAll();

        assertThat(get("/admin/v1/upstreams", false).statusCode()).isEqualTo(401);
        assertThat(get("/admin/v1/upstreams", true).statusCode()).isEqualTo(200);
    }

    @Test
    void should_list_active_subscription_when_subscribed() throws Exception {
        startAll();
        CountDownLatch established = new CountDownLatch(1);

        ClientResponseObserver<SubscribeRequest, SubscriptionEvent> observer = new ClientResponseObserver<>() {

            @Override
            public void beforeStart(ClientCallStreamObserver<SubscribeRequest> requestStream) {}

            @Override
            public void onNext(SubscriptionEvent event) {
                established.countDown(); // 首条空事件 = 建立确认
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
        assertThat(established.await(5, TimeUnit.SECONDS)).isTrue();

        JsonNode subscriptions = json(get("/admin/v1/subscriptions", true)).get("subscriptions");
        assertThat(subscriptions.size()).isEqualTo(1);
        JsonNode subscription = subscriptions.get(0);

        assertThat(subscription.get("id").asLong()).isPositive();
        assertThat(subscription.get("subscriber").asText()).contains("127.0.0.1");
        assertThat(subscription.get("establishedAt").asText()).isNotEmpty();
        assertThat(subscription.get("services").get(0).get("namespace").asText())
                .isEqualTo(NAMESPACE);
        assertThat(subscription.get("services").get(0).get("service").asText()).isEqualTo(SERVICE);
        assertThat(subscription.get("labels").get("group").asText()).isEqualTo("a");
        assertThat(subscription.get("clusters").get(0).asText()).isEqualTo(CLUSTER);
    }

    @Test
    void should_remove_subscription_when_stream_cancelled() throws Exception {
        startAll();
        CountDownLatch established = new CountDownLatch(1);
        AtomicReference<ClientCallStreamObserver<SubscribeRequest>> requestStream = new AtomicReference<>();
        ClientResponseObserver<SubscribeRequest, SubscriptionEvent> observer = new ClientResponseObserver<>() {

            @Override
            public void beforeStart(ClientCallStreamObserver<SubscribeRequest> stream) {
                requestStream.set(stream);
            }

            @Override
            public void onNext(SubscriptionEvent event) {
                established.countDown();
            }

            @Override
            public void onError(Throwable error) {}

            @Override
            public void onCompleted() {}
        };
        subscribeStub.subscribe(SubscribeRequest.newBuilder().addServices(KEY).build(), observer);
        assertThat(established.await(5, TimeUnit.SECONDS)).isTrue();
        awaitUntil("subscription visible", () -> subscriptions().size() == 1);

        requestStream.get().cancel("test done", null);
        awaitUntil("subscription removed", () -> subscriptions().size() == 0);
    }

    @Test
    void should_list_service_view_and_detail_when_registered() throws Exception {
        startAll();
        register("i1", "i2");
        awaitUntil(
                "view visible",
                () -> serviceView().size() == 1
                        && serviceView().get(0).get("instanceCount").asInt() == 2);

        JsonNode view = serviceView().get(0);
        assertThat(view.get("cluster").asText()).isEqualTo(CLUSTER);
        assertThat(view.get("namespace").asText()).isEqualTo(NAMESPACE);
        assertThat(view.get("service").asText()).isEqualTo(SERVICE);

        JsonNode detail = json(get(detailPath(), true));
        assertThat(detail.get("clusters").size()).isEqualTo(1);
        assertThat(detail.get("clusters").get(0).get("cluster").asText()).isEqualTo(CLUSTER);
        assertThat(detail.get("clusters").get(0).get("instances").size()).isEqualTo(2);
        assertThat(detail.get("clusters")
                        .get(0)
                        .get("instances")
                        .get(0)
                        .get("instanceId")
                        .asText())
                .isEqualTo("i1");

        JsonNode unknown = json(get("/admin/v1/services/default/unknown", true));
        assertThat(unknown.get("clusters").size()).isZero();
    }

    @Test
    void should_expose_upstream_freshness_and_management_disabled() throws Exception {
        startAll();

        JsonNode upstreams = json(get("/admin/v1/upstreams", true));
        assertThat(upstreams.get("upstreams").size()).isEqualTo(1);
        assertThat(upstreams.get("upstreams").get(0).get("cluster").asText()).isEqualTo(CLUSTER);
        assertThat(upstreams.get("upstreams").get(0).get("state").asText()).isEqualTo("SYNCED");
        assertThat(upstreams.get("management").get("enabled").asBoolean()).isFalse();
        assertThat(upstreams.get("management").get("state").asText()).isEqualTo("DISABLED");
    }

    @Test
    void should_return_client_error_when_blank_path_or_unknown_route() throws Exception {
        startAll();

        assertThat(get("/admin/v1/services/%20/" + SERVICE, true).statusCode()).isEqualTo(400);
        assertThat(get("/admin/v1/unknown", true).statusCode()).isEqualTo(404);
    }

    // ---- 起停与驱动 ----

    private void startAll() throws IOException {
        int registryPort = freePort();
        int peerPort = freePort();
        startRegistry(registryPort, peerPort);
        startDiscovery(peerPort);
        awaitDiscoveryReady();
    }

    private void startRegistry(int externalPort, int peerPort) {
        registryContext = new SpringApplicationBuilder(ArtemisRegistryServer.class)
                .run(
                        "--spring.config.location=optional:classpath:/discovery-admin-it-registry.properties",
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
        registryStub = RegistryServiceGrpc.newBlockingStub(registryChannel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(basicAuth("artemis", "artemis")));
    }

    private void startDiscovery(int peerPort) {
        discoveryContext = new SpringApplicationBuilder(ArtemisDiscoveryServer.class)
                .run(
                        "--spring.application.name=artemis-discovery",
                        "--server.port=0",
                        "--management.server.port=0",
                        "--spring.grpc.server.port=0",
                        "--artemis.discovery.registry.clusters={\"test-cluster\":\"127.0.0.1:" + peerPort + "\"}",
                        "--artemis.discovery.upstream.reconnectBackoffMinMs=100",
                        "--artemis.discovery.upstream.reconnectBackoffMaxMs=200",
                        "--artemis.discovery.auth.enabled=true",
                        "--artemis.discovery.auth.username=artemis",
                        "--artemis.discovery.auth.password=artemis",
                        "--management.tracing.export.otlp.enabled=false",
                        "--management.otlp.metrics.export.enabled=false");
        discoveryChannel = ManagedChannelBuilder.forAddress("127.0.0.1", discoveryPort())
                .usePlaintext()
                .build();
        var auth = MetadataUtils.newAttachHeadersInterceptor(basicAuth("artemis", "artemis"));
        queryStub = DiscoveryQueryServiceGrpc.newBlockingStub(discoveryChannel).withInterceptors(auth);
        subscribeStub =
                DiscoverySubscriptionServiceGrpc.newStub(discoveryChannel).withInterceptors(auth);
        objectMapper = discoveryContext.getBean(ObjectMapper.class);
    }

    private int discoveryPort() {
        Map<String, GrpcServerLifecycle> lifecycles = discoveryContext.getBeansOfType(GrpcServerLifecycle.class);
        return lifecycles.values().iterator().next().getPort();
    }

    private int httpPort() {
        return ((WebServerApplicationContext) discoveryContext).getWebServer().getPort();
    }

    private void awaitDiscoveryReady() {
        awaitUntil("discovery ready", () -> {
            try {
                queryStub.query(QueryRequest.newBuilder().addServices(KEY).build());
                return true;
            } catch (StatusRuntimeException e) {
                return false;
            }
        });
    }

    private void register(String... instanceIds) {
        var services = org.mydotey.ai.artemis.proto.common.v1.ServiceInstances.newBuilder()
                .setKey(KEY);
        for (String instanceId : instanceIds) {
            services.addInstances(Instance.newBuilder()
                    .setInstanceId(instanceId)
                    .setHost("10.0.0.1")
                    .setPort(8080));
        }
        assertThat(registryStub
                        .register(RegisterRequest.newBuilder()
                                .addServices(services)
                                .build())
                        .getStatus()
                        .getAck())
                .isEqualTo(Ack.ACK_SUCCESS);
    }

    private JsonNode subscriptions() {
        return safeJson("/admin/v1/subscriptions").get("subscriptions");
    }

    private JsonNode serviceView() {
        return safeJson("/admin/v1/services?namespace=" + NAMESPACE + "&cluster=" + CLUSTER)
                .get("services");
    }

    private JsonNode safeJson(String path) {
        try {
            return json(get(path, true));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpResponse<String> get(String path, boolean authenticated) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + httpPort() + path))
                .GET();
        if (authenticated) {
            builder.header("Authorization", AUTH);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) {
        assertThat(response.statusCode())
                .as("response body: %s", response.body())
                .isBetween(200, 299);
        return objectMapper.readTree(response.body());
    }

    private static String detailPath() {
        return "/admin/v1/services/" + NAMESPACE + "/" + SERVICE;
    }

    private static Metadata basicAuth(String username, String password) {
        Metadata headers = new Metadata();
        headers.put(
                Metadata.Key.of(BasicAuth.HEADER, Metadata.ASCII_STRING_MARSHALLER),
                BasicAuth.headerValue(username, password));
        return headers;
    }

    private static void awaitUntil(String what, java.util.function.BooleanSupplier condition) {
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
}
