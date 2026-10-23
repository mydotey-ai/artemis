package org.mydotey.ai.artemis.client.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.proto.replication.v1.ClusterMember;
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusResponse;
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusResponseData;
import org.mydotey.ai.artemis.proto.replication.v1.NodeState;
import org.mydotey.scf.facade.ConfigurationManagers;
import org.mydotey.scf.facade.SimpleConfigurationSources;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.source.stringproperty.memorymap.MemoryMapConfigurationSource;

/** HTTP 前置寻址传输：proto bytes 解析、Basic 头、url 依次重试、必配/非法值 fail-fast。 */
class HttpNodeListFetcherTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void should_parseNodeListAndSendAuth_when_httpBootstrapServed() throws Exception {
        AtomicInteger authChecks = new AtomicInteger();
        startServer(new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                assertThat(exchange.getRequestURI().getPath()).isEqualTo("/cluster/nodes");
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                if (authorization == null || !authorization.startsWith("Basic ")) {
                    authChecks.incrementAndGet();
                    exchange.sendResponseHeaders(401, -1);
                    exchange.close();
                    return;
                }
                byte[] body = GetNodeStatusResponse.newBuilder()
                        .setData(GetNodeStatusResponseData.newBuilder()
                                .addMembers(member("node-1", "10.0.0.1", 9090, NodeState.NODE_STATE_UP))
                                .addMembers(member("node-2", "10.0.0.2", 9090, NodeState.NODE_STATE_UNKNOWN))
                                .addMembers(member("node-3", "", 0, NodeState.NODE_STATE_UP)))
                        .build()
                        .toByteArray();
                exchange.getResponseHeaders().set("Content-Type", "application/x-protobuf");
                exchange.sendResponseHeaders(200, body.length);
                OutputStream stream = exchange.getResponseBody();
                stream.write(body);
                stream.close();
            }
        });

        HttpNodeListFetcher fetcher = fetcher(url(), "artemis", "secret");
        List<NodeEndpoint> endpoints = fetcher.fetch();

        assertThat(authChecks.get()).isZero(); // Basic 头已携带
        assertThat(endpoints).hasSize(2); // external 端点非法成员（node-3）被跳过
        assertThat(endpoints.get(0).address).isEqualTo("10.0.0.1:9090");
        assertThat(endpoints.get(0).priority).isEqualTo(NodeEndpoint.PRIORITY_UP);
        assertThat(endpoints.get(1).priority).isEqualTo(NodeEndpoint.PRIORITY_ALIVE); // UNKNOWN
    }

    @Test
    void should_tryNextUrl_when_firstFails() throws Exception {
        startServer(new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] body = GetNodeStatusResponse.newBuilder()
                        .setData(GetNodeStatusResponseData.newBuilder()
                                .addMembers(member("node-1", "10.0.0.9", 9090, NodeState.NODE_STATE_UP)))
                        .build()
                        .toByteArray();
                exchange.sendResponseHeaders(200, body.length);
                OutputStream stream = exchange.getResponseBody();
                stream.write(body);
                stream.close();
            }
        });

        // 第一个 url 指向无人监听端口（连接失败），第二个为活服务：依次重试后成功
        HttpNodeListFetcher fetcher = fetcher("http://127.0.0.1:1," + url());
        List<NodeEndpoint> endpoints = fetcher.fetch();
        assertThat(endpoints).hasSize(1);
        assertThat(endpoints.get(0).address).isEqualTo("10.0.0.9:9090");
    }

    @Test
    void should_tryNextUrl_when_serverNotReady() throws Exception {
        // 第一个 url 返回 503（节点未就绪拒绝寻址应答），第二个为就绪服务：与连接失败同链路换 url 重试
        HttpServer notReady = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        notReady.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
            }
        });
        notReady.start();
        try {
            startServer(new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    byte[] body = GetNodeStatusResponse.newBuilder()
                            .setData(GetNodeStatusResponseData.newBuilder()
                                    .addMembers(member("node-1", "10.0.0.9", 9090, NodeState.NODE_STATE_UP)))
                            .build()
                            .toByteArray();
                    exchange.sendResponseHeaders(200, body.length);
                    OutputStream stream = exchange.getResponseBody();
                    stream.write(body);
                    stream.close();
                }
            });

            HttpNodeListFetcher fetcher =
                    fetcher("http://127.0.0.1:" + notReady.getAddress().getPort() + "," + url());
            List<NodeEndpoint> endpoints = fetcher.fetch();
            assertThat(endpoints).hasSize(1);
            assertThat(endpoints.get(0).address).isEqualTo("10.0.0.9:9090");
        } finally {
            notReady.stop(0);
        }
    }

    @Test
    void should_failFast_when_addressesMissingOrInvalid() {
        assertThatThrownBy(() -> fetcher(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(HttpNodeListFetcher.KEY_REGISTRY_ADDRESSES);
        assertThatThrownBy(() -> fetcher("not-a-url"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(HttpNodeListFetcher.KEY_REGISTRY_ADDRESSES);
    }

    @Test
    void should_appendNodesPath_withUrlPathPrefix() {
        assertThat(HttpNodeListFetcher.nodesUrl("http://host:8080")).isEqualTo("http://host:8080/cluster/nodes");
        assertThat(HttpNodeListFetcher.nodesUrl("http://host:8080/")).isEqualTo("http://host:8080/cluster/nodes");
        assertThat(HttpNodeListFetcher.nodesUrl("http://host/artemis")).isEqualTo("http://host/artemis/cluster/nodes");
    }

    // ---- 设施 ----

    private static ClusterMember member(String nodeId, String host, int port, NodeState state) {
        return ClusterMember.newBuilder()
                .setNodeId(nodeId)
                .setExternalHost(host)
                .setExternalPort(port)
                .setState(state)
                .build();
    }

    private void startServer(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static HttpNodeListFetcher fetcher(String addresses) {
        return fetcher(addresses, null, null);
    }

    private static HttpNodeListFetcher fetcher(String addresses, String username, String password) {
        MemoryMapConfigurationSource source = SimpleConfigurationSources.newMemoryMapSource();
        if (addresses != null) {
            source.setPropertyValue(HttpNodeListFetcher.KEY_REGISTRY_ADDRESSES, addresses);
        }
        if (username != null) {
            source.setPropertyValue(ClientProperties.KEY_AUTH_USERNAME, username);
            source.setPropertyValue(ClientProperties.KEY_AUTH_PASSWORD, password);
        }
        return new HttpNodeListFetcher(new StringProperties(ConfigurationManagers.newManager(
                "test-fetcher-" + System.nanoTime(), java.util.Collections.singletonMap(1, source))));
    }
}
