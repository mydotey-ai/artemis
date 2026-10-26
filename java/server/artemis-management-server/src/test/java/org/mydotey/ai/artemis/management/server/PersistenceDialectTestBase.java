package org.mydotey.ai.artemis.management.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.management.application.PersistenceInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 双方言 DB IT 基类：子类声明 Testcontainers 容器与 datasource 属性，
 * 共享断言 Flyway V1 applied + DB 态可观测（bean 与状态 API 双出口）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
abstract class PersistenceDialectTestBase {

    private static final String AUTH = BasicAuth.headerValue("artemis", "artemis");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int httpPort;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PersistenceInfo persistenceInfo;

    @Test
    void should_run_flyway_and_report_database_mode() {
        assertThat(persistenceInfo.mode()).isEqualTo(PersistenceInfo.Mode.DATABASE);
        assertThat(persistenceInfo.databaseProduct()).isEqualTo(expectedDatabaseProduct());
        assertThat(persistenceInfo.schemaVersion()).isEqualTo("5");
    }

    @Test
    void should_expose_persistence_status_when_http_api_called() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + httpPort + "/admin/v1/status/persistence"))
                .header("Authorization", AUTH)
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("mode").asText()).isEqualTo("DATABASE");
        assertThat(body.get("databaseProduct").asText()).isEqualTo(expectedDatabaseProduct());
        assertThat(body.get("schemaVersion").asText()).isEqualTo("5");
    }

    abstract String expectedDatabaseProduct();
}
