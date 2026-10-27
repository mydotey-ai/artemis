package org.mydotey.ai.artemis.management.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 双方言 RBAC IT（v1.0 phase-2）：V7 seed ADMIN → admin 建多角色用户 → 角色 × 资源 × 动作
 * 允许/拒绝 + 401/403 分层 + 账户管理审计 + 凭证/角色变更即时生效。用例有状态依赖（建户/改密），
 * @Order 显式定序（同 AccountFlowIT 先例）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class RbacIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int httpPort;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @Order(1)
    void should_seed_admin_role_when_v7_migration() throws Exception {
        HttpResponse<String> response = send("GET", "/admin/v1/users", null, "artemis", "artemis");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode users = objectMapper.readTree(response.body()).get("users");
        assertThat(users).hasSize(1);
        assertThat(users.get(0).get("username").asText()).isEqualTo("artemis");
        assertThat(users.get(0).get("role").asText()).isEqualTo("ADMIN");
        assertThat(users.get(0).has("passwordHash")).isFalse();
    }

    @Test
    @Order(2)
    void should_enforce_role_matrix_on_admin_v1_endpoints() throws Exception {
        assertThat(createUser("ops", "ops-pass-123", "OPERATOR")).isEqualTo(200);
        assertThat(createUser("viewer", "viewer-pass-1", "VIEWER")).isEqualTo(200);
        assertThat(createUser("ops", "ops-pass-123", "OPERATOR")).isEqualTo(409);
        assertThat(send(
                                "POST",
                                "/admin/v1/users",
                                "{\"username\":\"x\",\"password\":\"valid-pass-1\",\"role\":\"ROOT\"}",
                                "artemis",
                                "artemis")
                        .statusCode())
                .isEqualTo(400);

        // viewer：读允许、写与审计/账户管理拒绝
        assertThat(send("GET", "/admin/v1/ejections", null, "viewer", "viewer-pass-1")
                        .statusCode())
                .isEqualTo(200);
        assertThat(send("POST", "/admin/v1/ejections", ejectBody(), "viewer", "viewer-pass-1")
                        .statusCode())
                .isEqualTo(403);
        assertThat(send("GET", "/admin/v1/audit-logs", null, "viewer", "viewer-pass-1")
                        .statusCode())
                .isEqualTo(403);
        assertThat(send("GET", "/admin/v1/users", null, "viewer", "viewer-pass-1")
                        .statusCode())
                .isEqualTo(403);

        // operator：治理写允许、审计读允许、账户管理拒绝
        assertThat(send("POST", "/admin/v1/ejections", ejectBody(), "ops", "ops-pass-123")
                        .statusCode())
                .isEqualTo(200);
        assertThat(send("POST", "/admin/v1/route-rules", routeRuleBody(), "ops", "ops-pass-123")
                        .statusCode())
                .isEqualTo(201);
        assertThat(send("GET", "/admin/v1/audit-logs", null, "ops", "ops-pass-123")
                        .statusCode())
                .isEqualTo(200);
        assertThat(send("GET", "/admin/v1/users", null, "ops", "ops-pass-123").statusCode())
                .isEqualTo(403);

        // admin：全通
        assertThat(send("GET", "/admin/v1/users", null, "artemis", "artemis").statusCode())
                .isEqualTo(200);
        HttpResponse<String> roles = send("GET", "/admin/v1/roles", null, "artemis", "artemis");
        assertThat(roles.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(roles.body()).get("roles").toString()).contains("ADMIN", "OPERATOR", "VIEWER");
    }

    @Test
    @Order(3)
    void should_return_401_when_unauthenticated_or_wrong_credential() throws Exception {
        // 无凭证/错凭证 = 401（认证层先行）；已认证越权 = 403（判权层，见 Order(2)）
        assertThat(send("GET", "/admin/v1/ejections", null, null, null).statusCode())
                .isEqualTo(401);
        assertThat(send("GET", "/admin/v1/ejections", null, "artemis", "wrong-pass")
                        .statusCode())
                .isEqualTo(401);
    }

    @Test
    @Order(4)
    void should_record_audit_when_user_administered() throws Exception {
        assertThat(send("PUT", "/admin/v1/users/ops/role", "{\"role\":\"OPERATOR\"}", "artemis", "artemis")
                        .statusCode())
                .isEqualTo(200);
        assertThat(send(
                                "PUT",
                                "/admin/v1/users/ops/password",
                                "{\"newPassword\":\"reset-pass-99\"}",
                                "artemis",
                                "artemis")
                        .statusCode())
                .isEqualTo(200);
        // 重置后旧凭证立即失效（认证缓存清槽端到端）
        assertThat(send("GET", "/admin/v1/ejections", null, "ops", "ops-pass-123")
                        .statusCode())
                .isEqualTo(401);
        assertThat(send("GET", "/admin/v1/ejections", null, "ops", "reset-pass-99")
                        .statusCode())
                .isEqualTo(200);

        assertThat(createUser("temp", "temp-pass-123", "VIEWER")).isEqualTo(200);
        assertThat(send("DELETE", "/admin/v1/users/temp", null, "artemis", "artemis")
                        .statusCode())
                .isEqualTo(200);
        assertThat(send("DELETE", "/admin/v1/users/artemis", null, "artemis", "artemis")
                        .statusCode())
                .isEqualTo(409);

        HttpResponse<String> audit = send("GET", "/admin/v1/audit-logs?limit=100", null, "artemis", "artemis");
        assertThat(audit.statusCode()).isEqualTo(200);
        String body = audit.body();
        assertThat(body)
                .contains("CREATE_USER", "UPDATE_USER_ROLE", "RESET_USER_PASSWORD", "DELETE_USER")
                .contains("account:temp", "account:ops");
    }

    private int createUser(String username, String password, String role) throws Exception {
        return send(
                        "POST",
                        "/admin/v1/users",
                        "{\"username\":\"" + username + "\",\"password\":\"" + password + "\",\"role\":\"" + role
                                + "\"}",
                        "artemis",
                        "artemis")
                .statusCode();
    }

    private static String ejectBody() {
        return "{\"scope\":\"INSTANCE\",\"namespace\":\"default\",\"service\":\"rbac-demo\",\"instanceId\":\"i-1\"}";
    }

    private static String routeRuleBody() {
        return "{\"namespace\":\"default\",\"service\":\"rbac-demo\",\"name\":\"rule-a\","
                + "\"targets\":[{\"selector\":{},\"unreleasedWeight\":10000}]}";
    }

    private HttpResponse<String> send(String method, String path, String body, String username, String password)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + httpPort + path))
                .header("Content-Type", "application/json")
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (username != null) {
            builder.header("Authorization", BasicAuth.headerValue(username, password));
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
