package org.mydotey.ai.artemis.discovery.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryNodesResponse;
import org.mydotey.ai.artemis.registry.server.ArtemisRegistryServer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 静态节点目录 HTTP 端点（ADR-020）：readiness 门控、外部 Basic 认证、protobuf 内容。
 */
class DiscoveryNodesHttpIT {

    private static final String AUTH = BasicAuth.headerValue("artemis", "artemis");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path tempDir;

    private ConfigurableApplicationContext registryContext;
    private ConfigurableApplicationContext discoveryContext;

    @AfterEach
    void tearDown() {
        if (discoveryContext != null) {
            discoveryContext.close();
        }
        if (registryContext != null) {
            registryContext.close();
        }
    }

    @Test
    void should_serve_discovery_nodes_over_http() throws Exception {
        int peerPort = freePort();
        startRegistry(peerPort);
        startDiscovery(peerPort, "127.0.0.1:9095,127.0.0.1:9096");

        assertThat(get(false).statusCode()).isEqualTo(401);
        HttpResponse<byte[]> response = awaitReady();
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse("")).contains("application/x-protobuf");

        DiscoveryNodesResponse nodes = DiscoveryNodesResponse.parseFrom(response.body());
        assertThat(nodes.getNodesCount()).isEqualTo(2);
        assertThat(nodes.getNodes(0).getHost()).isEqualTo("127.0.0.1");
        assertThat(nodes.getNodes(0).getPort()).isEqualTo(9095);
        assertThat(nodes.getNodes(1).getPort()).isEqualTo(9096);
    }

    @Test
    void should_return_503_when_not_ready() throws Exception {
        startDiscovery(freePort(), "127.0.0.1:9095"); // 上游不可达 → 永不 ready
        assertThat(get(true).statusCode()).isEqualTo(503);
    }

    private HttpResponse<byte[]> awaitReady() throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        HttpResponse<byte[]> response = get(true);
        while (response.statusCode() != 200 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            response = get(true);
        }
        return response;
    }

    private HttpResponse<byte[]> get(boolean authenticated) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + httpPort() + "/cluster/nodes"))
                .GET();
        if (authenticated) {
            builder.header("Authorization", AUTH);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private int httpPort() {
        return ((WebServerApplicationContext) discoveryContext).getWebServer().getPort();
    }

    private void startRegistry(int peerPort) {
        registryContext = new SpringApplicationBuilder(ArtemisRegistryServer.class)
                .run(
                        // 覆盖 classpath application.properties（discovery 模块的健康组含 discoveryReadiness，
                        // 不适用于 registry）；与既有 DiscoveryAdminIT 同法
                        "--spring.config.location=optional:classpath:/discovery-admin-it-registry.properties",
                        "--spring.application.name=artemis-registry",
                        "--spring.main.web-application-type=none",
                        "--spring.grpc.server.shutdown.grace-period=1s",
                        "--management.server.port=0",
                        "--management.tracing.export.otlp.enabled=false",
                        "--management.otlp.metrics.export.enabled=false",
                        "--spring.grpc.server.port=0",
                        "--artemis.registry.internal.port=" + peerPort,
                        "--artemis.registry.internal.address=127.0.0.1",
                        "--artemis.registry.snapshot.path=" + tempDir.resolve("registry-snapshot.bin"),
                        "--artemis.registry.shutdown.drainMs=0");
    }

    private void startDiscovery(int upstreamPort, String nodes) {
        discoveryContext = new SpringApplicationBuilder(ArtemisDiscoveryServer.class)
                .run(
                        "--spring.application.name=artemis-discovery",
                        "--server.port=0",
                        "--management.server.port=0",
                        "--spring.grpc.server.port=0",
                        "--artemis.discovery.registry.clusters={\"test-cluster\":\"127.0.0.1:" + upstreamPort + "\"}",
                        "--artemis.discovery.upstream.reconnectBackoffMinMs=100",
                        "--artemis.discovery.upstream.reconnectBackoffMaxMs=200",
                        "--artemis.discovery.nodes=" + nodes,
                        "--artemis.discovery.auth.enabled=true",
                        "--artemis.discovery.auth.username=artemis",
                        "--artemis.discovery.auth.password=artemis",
                        "--management.tracing.export.otlp.enabled=false",
                        "--management.otlp.metrics.export.enabled=false");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
