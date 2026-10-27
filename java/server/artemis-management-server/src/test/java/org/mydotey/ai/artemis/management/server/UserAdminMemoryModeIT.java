package org.mydotey.ai.artemis.management.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 内存态 RBAC 边界 IT（v1.0 phase-2）：单管理员配置凭证全权（无判权 filter），
 * 用户管理端点 409；自助改密 409 为既有语义不回退。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class UserAdminMemoryModeIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int httpPort;

    @Test
    void should_reject_user_administration_when_memory_mode() throws Exception {
        assertThat(send("GET", "/admin/v1/users", null).statusCode()).isEqualTo(409);
        assertThat(send("GET", "/admin/v1/roles", null).statusCode()).isEqualTo(409);
        assertThat(send(
                                "POST",
                                "/admin/v1/users",
                                "{\"username\":\"x\",\"password\":\"valid-pass-1\",\"role\":\"VIEWER\"}")
                        .statusCode())
                .isEqualTo(409);
        HttpResponse<String> roleChange = send("PUT", "/admin/v1/users/artemis/role", "{\"role\":\"VIEWER\"}");
        assertThat(roleChange.statusCode()).as(roleChange.body()).isEqualTo(409);
        assertThat(send("DELETE", "/admin/v1/users/artemis", null).statusCode()).isEqualTo(409);
        assertThat(send(
                                "PUT",
                                "/admin/v1/account/password",
                                "{\"oldPassword\":\"artemis\",\"newPassword\":\"new-s3cret-9\"}")
                        .statusCode())
                .isEqualTo(409);
    }

    @Test
    void should_keep_full_access_for_configured_admin_when_memory_mode() throws Exception {
        // 内存态无判权：本地资源读正常（不经 registry 下游）
        assertThat(send("GET", "/admin/v1/ejections", null).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + httpPort + path))
                .header("Content-Type", "application/json")
                .header("Authorization", BasicAuth.headerValue("artemis", "artemis"))
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
