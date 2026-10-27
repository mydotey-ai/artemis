package org.mydotey.ai.artemis.management.server.http;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.mydotey.ai.artemis.management.domain.ServiceKey;
import org.mydotey.ai.artemis.management.domain.Subscription;
import org.mydotey.ai.artemis.management.domain.SubscriptionQueryPort;
import org.mydotey.ai.artemis.management.domain.SubscriptionSource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 订阅关系查询出站端口实现（架构 §2.2 management → discovery 只读通道）：逐 discovery
 * 节点拉取 {@code GET /admin/v1/subscriptions}（discovery 发现管理 API §3 wire 形态），
 * 虚拟线程并行、per-source fail-open（单源失败以 error 承载，不拖垮其余源）。
 * 端点/凭证/超时均静态，客户端与认证头构造时固化。
 */
public final class DiscoveryAdminClient implements SubscriptionQueryPort, AutoCloseable {

    private static final String SUBSCRIPTIONS_PATH = "/admin/v1/subscriptions";

    private final List<String> endpoints;
    private final RestClient[] clients; // per endpoint，构造时固化（RestClient 不可变可复用）

    /** 共享命名虚拟线程 executor（任务数 = 源数，配置有界；tech-java executor 命名纪律）。 */
    private final ExecutorService executor;

    public DiscoveryAdminClient(DiscoveryClientConfig config, RestClient.Builder builder) {
        this.endpoints = config.endpoints();
        String username = config.username();
        String password = config.password();
        JdkClientHttpRequestFactory factory = requestFactory(config.timeoutMs());
        this.clients = endpoints.stream()
                .map(endpoint -> builder.clone()
                        .baseUrl("http://" + endpoint)
                        .requestFactory(factory)
                        .defaultHeaders(headers -> {
                            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                            if (username != null && !username.isBlank()) {
                                headers.setBasicAuth(username, password);
                            }
                        })
                        .build())
                .toArray(RestClient[]::new);
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("artemis-management-discovery-", 0).factory());
    }

    @Override
    public void close() {
        executor.shutdown(); // 停机收口；在途拉取短生命周期，服务关闭后无害
    }

    private static JdkClientHttpRequestFactory requestFactory(long timeoutMs) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return factory;
    }

    @Override
    public List<SubscriptionSource> subscriptions() {
        if (endpoints.isEmpty()) {
            return List.of();
        }
        // 源典型 1..3 个；并行防单坏源超时拖慢整视图（fetch 有界：connect/read 双超时）
        List<Future<SubscriptionSource>> futures = new ArrayList<>(clients.length);
        for (int i = 0; i < clients.length; i++) {
            String endpoint = endpoints.get(i);
            RestClient client = clients[i];
            futures.add(executor.submit(() -> fetch(endpoint, client)));
        }
        List<SubscriptionSource> sources = new ArrayList<>(futures.size());
        for (Future<SubscriptionSource> future : futures) {
            sources.add(await(future));
        }
        return sources;
    }

    private static SubscriptionSource fetch(String endpoint, RestClient client) {
        try {
            SubscriptionsDto body =
                    client.get().uri(SUBSCRIPTIONS_PATH).retrieve().body(SubscriptionsDto.class);
            return new SubscriptionSource(endpoint, null, toDomain(body));
        } catch (RestClientException | IllegalArgumentException e) {
            return new SubscriptionSource(endpoint, errorText(e), List.of());
        }
    }

    private static SubscriptionSource await(Future<SubscriptionSource> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("subscription query interrupted", e);
        } catch (ExecutionException e) {
            // fetch 已内部捕获；到这说明实现缺陷，直接抛出（500 排障）
            throw new IllegalStateException("subscription query failed", e.getCause());
        }
    }

    private static List<Subscription> toDomain(SubscriptionsDto body) {
        if (body == null || body.subscriptions() == null) {
            return List.of();
        }
        return body.subscriptions().stream()
                .map(subscription -> new Subscription(
                        subscription.id(),
                        subscription.subscriber(),
                        subscription.establishedAt(),
                        subscription.services() == null
                                ? List.of()
                                : subscription.services().stream()
                                        .map(key -> new ServiceKey(key.namespace(), key.service()))
                                        .toList(),
                        subscription.labels(),
                        subscription.clusters()))
                .toList();
    }

    private static String errorText(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /** discovery wire 形态（discovery 发现管理 API §3），仅本类反序列化边界使用。 */
    record SubscriptionsDto(List<SubscriptionDto> subscriptions) {}

    record SubscriptionDto(
            long id,
            String subscriber,
            Instant establishedAt,
            List<ServiceKeyDto> services,
            Map<String, String> labels,
            List<String> clusters) {}

    record ServiceKeyDto(String namespace, String service) {}
}
