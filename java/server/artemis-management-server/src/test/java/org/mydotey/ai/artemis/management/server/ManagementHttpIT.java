package org.mydotey.ai.artemis.management.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * management IT 共享 HTTP 基建：随机端口注入、Basic 认证请求与 JSON 断言包装。
 * 与 {@link RegistryFixture} 同理抽为同 JVM 多 IT 类共用（v0.4 phase-6 review #4）。
 */
abstract class ManagementHttpIT {

    protected static final String AUTH = BasicAuth.headerValue("artemis", "artemis");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    protected int httpPort;

    @Autowired
    protected ObjectMapper objectMapper;

    protected final HttpResponse<String> get(String path) throws Exception {
        return get(path, true);
    }

    protected final HttpResponse<String> get(String path, boolean authenticated) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET(), authenticated);
    }

    protected final HttpResponse<String> post(String path, String body) throws Exception {
        return send(
                HttpRequest.newBuilder(uri(path))
                        .POST(
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body)),
                true);
    }

    protected final HttpResponse<String> send(HttpRequest.Builder builder, boolean authenticated) throws Exception {
        if (authenticated) {
            builder.header("Authorization", AUTH);
        }
        builder.header("Content-Type", "application/json");
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected final URI uri(String path) {
        return URI.create("http://localhost:" + httpPort + path);
    }

    protected final JsonNode json(HttpResponse<String> response) throws IOException {
        assertThat(response.statusCode())
                .as("response body: %s", response.body())
                .isBetween(200, 299);
        return objectMapper.readTree(response.body());
    }
}
