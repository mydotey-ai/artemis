package org.mydotey.ai.artemis.management.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * 双方言账户流 IT（ADR-021）：seed 默认账户 → 改密接管生效 → 跨重启（同 DB 新 context）
 * 账户保留且配置凭证失效。用例有状态依赖（改密不可逆），@Order 显式定序。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class AccountFlowIT {

    private static final String FIRST_PASSWORD = "new-s3cret-9";
    private static final String RESTART_PASSWORD = "restart-pass-9";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int httpPort;

    @Autowired
    private ObjectMapper objectMapper;

    abstract String jdbcUrl();

    abstract String jdbcUsername();

    abstract String jdbcPassword();

    @Test
    @Order(1)
    void should_seed_default_account_and_login_when_database_mode() throws Exception {
        // DB 态首启：配置凭证（默认 artemis/artemis）seed 物化进账户表后可登录
        assertThat(status(httpPort, "artemis", "artemis")).isEqualTo(200);
    }

    @Test
    @Order(2)
    void should_honor_password_change_when_database_mode() throws Exception {
        int changeStatus = changePassword(httpPort, "artemis", "artemis", FIRST_PASSWORD);

        assertThat(changeStatus).isEqualTo(200);
        assertThat(status(httpPort, "artemis", "artemis")).isEqualTo(401);
        assertThat(status(httpPort, "artemis", FIRST_PASSWORD)).isEqualTo(200);
    }

    @Test
    @Order(3)
    void should_retain_account_across_restart() throws Exception {
        assertThat(changePassword(httpPort, "artemis", FIRST_PASSWORD, RESTART_PASSWORD))
                .isEqualTo(200);

        // 重启语义：同 DB 起新 context；配置凭证改为自定义——验证表是唯一源、seed 不覆盖
        try (ConfigurableApplicationContext restarted = new SpringApplicationBuilder(ArtemisManagementServer.class)
                .properties(
                        "server.port=0",
                        "management.server.port=0",
                        "spring.datasource.url=" + jdbcUrl(),
                        "spring.datasource.username=" + jdbcUsername(),
                        "spring.datasource.password=" + jdbcPassword(),
                        "artemis.management.auth.username=changed-admin",
                        "artemis.management.auth.password=changed-pass")
                .run()) {
            int port = ((WebServerApplicationContext) restarted).getWebServer().getPort();

            assertThat(status(port, "artemis", RESTART_PASSWORD)).isEqualTo(200);
            assertThat(status(port, "artemis", "artemis")).isEqualTo(401);
            // 配置凭证在 DB 态失效（表非空不 re-seed）
            assertThat(status(port, "changed-admin", "changed-pass")).isEqualTo(401);
        }
    }

    private int status(int port, String username, String password) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/admin/v1/status/persistence"))
                .header("Authorization", BasicAuth.headerValue(username, password))
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private int changePassword(int port, String oldPassword, String newPassword) throws Exception {
        return changePassword(port, "artemis", oldPassword, newPassword);
    }

    private int changePassword(int port, String username, String oldPassword, String newPassword) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/admin/v1/account/password"))
                .header("Authorization", BasicAuth.headerValue(username, oldPassword))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(
                        Map.of("oldPassword", oldPassword, "newPassword", newPassword))))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
