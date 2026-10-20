package org.mydotey.ai.artemis.management.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.management.domain.RegistryQueryException;
import org.mydotey.ai.artemis.management.server.TestProperties;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.ErrorInfo;
import org.mydotey.ai.artemis.proto.common.v1.ResponseStatus;
import org.mydotey.ai.artemis.proto.management.v1.ListServicesRequest;
import org.mydotey.ai.artemis.proto.management.v1.ListServicesResponse;
import org.mydotey.ai.artemis.proto.management.v1.RegistryQueryServiceGrpc;

class RegistryQueryGrpcClientTest {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of(BasicAuth.HEADER, Metadata.ASCII_STRING_MARSHALLER);

    private final AtomicReference<String> authorization = new AtomicReference<>();
    private Server server;

    @BeforeEach
    void setUp() throws IOException {
        RegistryQueryServiceGrpc.RegistryQueryServiceImplBase service =
                new RegistryQueryServiceGrpc.RegistryQueryServiceImplBase() {
                    @Override
                    public void listServices(
                            ListServicesRequest request, StreamObserver<ListServicesResponse> observer) {
                        observer.onNext(ListServicesResponse.newBuilder()
                                .setStatus(ResponseStatus.newBuilder()
                                        .setAck(Ack.ACK_FAIL)
                                        .setError(ErrorInfo.newBuilder()
                                                .setCode("INVALID_ARGUMENT")
                                                .setMessage("boom")))
                                .build());
                        observer.onCompleted();
                    }
                };
        ServerInterceptor captureAuth = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                authorization.set(headers.get(AUTHORIZATION));
                return next.startCall(call, headers);
            }
        };
        server = ServerBuilder.forPort(0)
                .addService(ServerInterceptors.intercept(service, captureAuth))
                .build()
                .start();
    }

    @AfterEach
    void tearDown() {
        server.shutdownNow();
    }

    @Test
    void should_map_fail_status_when_registry_returns_fail() {
        try (var client = new RegistryQueryGrpcClient(config("localhost:" + server.getPort(), 3_000))) {
            assertThatThrownBy(() -> client.listServices(""))
                    .isInstanceOf(RegistryQueryException.class)
                    .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((RegistryQueryException) e).code())
                            .isEqualTo(RegistryQueryException.REGISTRY_ERROR));
        }
    }

    @Test
    void should_attach_basic_auth_when_username_configured() {
        try (var client =
                new RegistryQueryGrpcClient(config("localhost:" + server.getPort(), 3_000, "peer", "secret"))) {
            assertThatThrownBy(() -> client.listServices("")).isInstanceOf(RegistryQueryException.class);
            assertThat(authorization.get()).isEqualTo(BasicAuth.headerValue("peer", "secret"));
        }
    }

    @Test
    void should_not_attach_auth_when_username_blank() {
        try (var client = new RegistryQueryGrpcClient(config("localhost:" + server.getPort(), 3_000))) {
            assertThatThrownBy(() -> client.listServices("")).isInstanceOf(RegistryQueryException.class);
            assertThat(authorization.get()).isNull();
        }
    }

    @Test
    void should_map_unavailable_when_registry_down() {
        try (var client = new RegistryQueryGrpcClient(config("localhost:1", 200))) {
            assertThatThrownBy(() -> client.listServices(""))
                    .isInstanceOf(RegistryQueryException.class)
                    .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((RegistryQueryException) e).code())
                            .isEqualTo(RegistryQueryException.REGISTRY_UNAVAILABLE));
        }
    }

    @Test
    void should_report_unavailable_when_endpoint_not_configured() {
        try (var client = new RegistryQueryGrpcClient(new RegistryClientConfig(TestProperties.empty()))) {
            assertThatThrownBy(() -> client.listServices(""))
                    .isInstanceOf(RegistryQueryException.class)
                    .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((RegistryQueryException) e).code())
                            .isEqualTo(RegistryQueryException.REGISTRY_UNAVAILABLE));
        }
    }

    @Test
    void should_use_latest_credentials_when_changed() {
        TestProperties properties = TestProperties.mutable(
                java.util.Map.of(RegistryClientConfig.KEY_ENDPOINT, "localhost:" + server.getPort()));
        try (var client = new RegistryQueryGrpcClient(new RegistryClientConfig(properties.properties()))) {
            assertThatThrownBy(() -> client.listServices("")).isInstanceOf(RegistryQueryException.class);
            assertThat(authorization.get()).isNull();

            // 运行期轮换凭证：下一次调用即使用新值
            properties.set(RegistryClientConfig.KEY_USERNAME, "peer");
            properties.set(RegistryClientConfig.KEY_PASSWORD, "secret");
            assertThatThrownBy(() -> client.listServices("")).isInstanceOf(RegistryQueryException.class);
            assertThat(authorization.get()).isEqualTo(BasicAuth.headerValue("peer", "secret"));
        }
    }

    private static RegistryClientConfig config(String endpoint, long timeoutMs) {
        return config(endpoint, timeoutMs, null, null);
    }

    private static RegistryClientConfig config(String endpoint, long timeoutMs, String username, String password) {
        java.util.Map<String, String> values = new java.util.HashMap<>();
        values.put(RegistryClientConfig.KEY_ENDPOINT, endpoint);
        values.put(RegistryClientConfig.KEY_TIMEOUT_MS, String.valueOf(timeoutMs));
        if (username != null) {
            values.put(RegistryClientConfig.KEY_USERNAME, username);
            values.put(RegistryClientConfig.KEY_PASSWORD, password);
        }
        return new RegistryClientConfig(TestProperties.of(values));
    }
}
