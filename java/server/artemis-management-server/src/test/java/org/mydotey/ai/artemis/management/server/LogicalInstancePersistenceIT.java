package org.mydotey.ai.artemis.management.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.proto.management.v1.LogicalInstanceServiceGrpc;
import org.mydotey.ai.artemis.proto.management.v1.LogicalInstancesEvent;
import org.mydotey.ai.artemis.proto.management.v1.LogicalInstancesRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 双方言逻辑实例持久化 IT（v0.4 phase-4）：DB 态 upsert/delete/list 经统一 HTTP API 落库、
 * 审计可查、**跨 management 重启（同 DB 新 context）逻辑实例经流快照重下发**
 * （「management 重启恢复」语义，DB 态闭环）。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "artemis.management.grpc.port=0"})
abstract class LogicalInstancePersistenceIT {

    private static final String AUTH = BasicAuth.headerValue("artemis", "artemis");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int httpPort;

    @Autowired
    private ObjectMapper objectMapper;

    abstract String jdbcUrl();

    abstract String jdbcUsername();

    abstract String jdbcPassword();

    @Test
    void should_persist_logical_instances_and_audit_when_database_mode() throws Exception {
        assertThat(put("/admin/v1/logical-instances", body("ns-p", "svc-a", "li-1", "10.0.0.1"))
                        .statusCode())
                .isEqualTo(200);
        assertThat(put("/admin/v1/logical-instances", body("ns-p", "svc-a", "li-2", "10.0.0.2"))
                        .statusCode())
                .isEqualTo(200);
        assertThat(delete("/admin/v1/logical-instances?namespace=ns-p&service=svc-a&instanceId=li-2")
                        .statusCode())
                .isEqualTo(204);

        JsonNode list = json(get("/admin/v1/logical-instances?namespace=ns-p&service=svc-a"));
        assertThat(list.get("instances").size()).isEqualTo(1);
        assertThat(list.get("instances").get(0).get("instanceId").asText()).isEqualTo("li-1");
        assertThat(list.get("instances").get(0).get("host").asText()).isEqualTo("10.0.0.1");
        assertThat(list.get("instances").get(0).get("metadata").get("protocol").asText())
                .isEqualTo("http");

        JsonNode audits = json(get("/admin/v1/audit-logs?operation=LOGICAL_INSTANCE_DELETE"));
        assertThat(audits.get("logs").size()).isEqualTo(1);
        assertThat(audits.get("logs").get(0).get("target").asText()).isEqualTo("ns-p/svc-a/li-2");
    }

    @Test
    void should_restore_logical_instances_across_management_restart() throws Exception {
        put("/admin/v1/logical-instances", body("ns-r", "svc-r", "li-restart", "10.0.0.9"));

        // 重启语义：同 DB 新 context（EjectionPersistenceIT 模式）
        try (ConfigurableApplicationContext restarted = new SpringApplicationBuilder(ArtemisManagementServer.class)
                .properties(
                        "server.port=0",
                        "management.server.port=0",
                        "artemis.management.grpc.port=0",
                        "spring.datasource.url=" + jdbcUrl(),
                        "spring.datasource.username=" + jdbcUsername(),
                        "spring.datasource.password=" + jdbcPassword(),
                        "artemis.management.auth.username=changed-admin",
                        "artemis.management.auth.password=changed-pass")
                .run()) {
            WebServerApplicationContext web = (WebServerApplicationContext) restarted;
            int restartedPort = web.getWebServer().getPort();
            int grpcPort = restarted.getBeansOfType(GrpcServerLifecycle.class).values().stream()
                    .findFirst()
                    .orElseThrow()
                    .getPort();

            // 重启后逻辑实例仍可查（DB 态持久）
            JsonNode list = json(get(restartedPort, "/admin/v1/logical-instances?namespace=ns-r"));
            assertThat(list.get("instances").size()).isEqualTo(1);
            assertThat(list.get("instances").get(0).get("instanceId").asText()).isEqualTo("li-restart");

            // 流快照重下发：新订阅收含重启前逻辑实例的快照
            LogicalInstancesEvent snapshot = watchSnapshot(grpcPort);
            assertThat(snapshot.hasSnapshot()).isTrue();
            assertThat(snapshot.getSnapshot().getInstancesList().stream()
                            .anyMatch(instance -> "li-restart"
                                            .equals(instance.getInstance().getInstanceId())
                                    && "ns-r".equals(instance.getService().getNamespace())))
                    .isTrue();
        }
    }

    // ---- 设施 ----

    private static String body(String namespace, String service, String instanceId, String host) {
        return "{\"namespace\":\"" + namespace + "\",\"service\":\"" + service + "\",\"instanceId\":\"" + instanceId
                + "\",\"host\":\"" + host + "\",\"port\":8080,\"metadata\":{\"protocol\":\"http\"}}";
    }

    private HttpResponse<String> put(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(httpPort, path))
                .header("Authorization", AUTH)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(httpPort, path))
                .header("Authorization", AUTH)
                .DELETE()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return get(httpPort, path);
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(port, path))
                .header("Authorization", AUTH)
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(int port, String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode())
                .as("response body: %s", response.body())
                .isBetween(200, 299);
        return objectMapper.readTree(response.body());
    }

    /** 重启后 context 的逻辑实例流首个事件（必为快照）。 */
    private static LogicalInstancesEvent watchSnapshot(int grpcPort) throws Exception {
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", grpcPort)
                .usePlaintext()
                .build();
        try {
            BlockingQueue<LogicalInstancesEvent> queue = new LinkedBlockingQueue<>();
            io.grpc.stub.ClientCalls.asyncServerStreamingCall(
                    channel.newCall(
                            LogicalInstanceServiceGrpc.getWatchLogicalInstancesMethod(), io.grpc.CallOptions.DEFAULT),
                    LogicalInstancesRequest.newBuilder()
                            .setSubscriberId("logical-restart-it")
                            .build(),
                    new io.grpc.stub.StreamObserver<>() {
                        @Override
                        public void onNext(LogicalInstancesEvent event) {
                            queue.offer(event);
                        }

                        @Override
                        public void onError(Throwable t) {}

                        @Override
                        public void onCompleted() {}
                    });
            LogicalInstancesEvent event = queue.poll(10, TimeUnit.SECONDS);
            assertThat(event).isNotNull();
            return event;
        } finally {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
