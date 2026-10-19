package org.mydotey.ai.artemis.management.server.grpc;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.management.domain.RegistryQueryException;
import org.mydotey.ai.artemis.management.server.TestProperties;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.ErrorInfo;
import org.mydotey.ai.artemis.proto.common.v1.ResponseStatus;
import org.mydotey.ai.artemis.proto.management.v1.ListServicesRequest;
import org.mydotey.ai.artemis.proto.management.v1.ListServicesResponse;
import org.mydotey.ai.artemis.proto.management.v1.RegistryQueryServiceGrpc;

class RegistryQueryGrpcClientTest {

    private Server server;

    @BeforeEach
    void setUp() throws IOException {
        server = ServerBuilder.forPort(0)
                .addService(new RegistryQueryServiceGrpc.RegistryQueryServiceImplBase() {
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
                })
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
    void should_map_unavailable_when_registry_down() {
        try (var client = new RegistryQueryGrpcClient(config("localhost:1", 200))) {
            assertThatThrownBy(() -> client.listServices(""))
                    .isInstanceOf(RegistryQueryException.class)
                    .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((RegistryQueryException) e).code())
                            .isEqualTo(RegistryQueryException.REGISTRY_UNAVAILABLE));
        }
    }

    private static RegistryClientConfig config(String endpoint, long timeoutMs) {
        return new RegistryClientConfig(TestProperties.of(Map.of(
                RegistryClientConfig.KEY_ENDPOINT,
                endpoint,
                RegistryClientConfig.KEY_TIMEOUT_MS,
                String.valueOf(timeoutMs))));
    }
}
