package org.mydotey.ai.artemis.registry.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Executable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.registry.domain.Ack;
import org.mydotey.ai.artemis.registry.domain.ErrorInfo;
import org.mydotey.ai.artemis.registry.domain.HeartbeatRequest;
import org.mydotey.ai.artemis.registry.domain.Instance;
import org.mydotey.ai.artemis.registry.domain.InstanceError;
import org.mydotey.ai.artemis.registry.domain.InstanceKey;
import org.mydotey.ai.artemis.registry.domain.InstanceStatus;
import org.mydotey.ai.artemis.registry.domain.RegisterRequest;
import org.mydotey.ai.artemis.registry.domain.RegisterResponse;
import org.mydotey.ai.artemis.registry.domain.RegisterResponseData;
import org.mydotey.ai.artemis.registry.domain.ResponseStatus;
import org.mydotey.ai.artemis.registry.domain.ServiceInstanceErrors;
import org.mydotey.ai.artemis.registry.domain.ServiceKey;

/** mapper 往返与分层守护：dto（proto 生成消息）只出现在 server 接入层与 application 转换边界。 */
class RegistryProtoMapperTest {

    private final RegistryProtoMapper mapper = RegistryProtoMapper.INSTANCE;

    @Nested
    class ProtoToDomain {

        @Test
        void should_map_proto_to_domain_when_mapper_round_trip() {
            org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest protoRequest =
                    org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest.newBuilder()
                            .addServices(org.mydotey.ai.artemis.proto.common.v1.ServiceInstances.newBuilder()
                                    .setKey(protoKey())
                                    .addInstances(protoInstance("i1"))
                                    .addInstances(protoInstance("i2")))
                            .build();

            RegisterRequest domain = mapper.toDomain(protoRequest);

            assertThat(domain.services()).singleElement().satisfies(services -> {
                assertThat(services.key().namespace()).isEqualTo("default");
                assertThat(services.key().service()).isEqualTo("order-service");
                assertThat(services.instances()).hasSize(2);
            });
        }

        @Test
        void should_map_seq_when_heartbeat_request_mapped() {
            org.mydotey.ai.artemis.proto.registry.v1.HeartbeatRequest protoRequest =
                    org.mydotey.ai.artemis.proto.registry.v1.HeartbeatRequest.newBuilder()
                            .setSeq(7L)
                            .build();

            HeartbeatRequest domain = mapper.toDomain(protoRequest);

            assertThat(domain.seq()).isEqualTo(7L);
            assertThat(domain.services()).isEmpty();
        }

        @Test
        void should_map_same_name_classes_across_packages_when_mapping() {
            org.mydotey.ai.artemis.proto.common.v1.Instance proto = protoInstance("i1");

            Instance domain = mapper.toDomain(proto);

            assertThat(domain.instanceId()).isEqualTo("i1");
            assertThat(domain.host()).isEqualTo("10.0.0.1");
            assertThat(domain.port()).isEqualTo(8080);
            assertThat(domain.status()).isEqualTo(InstanceStatus.UP);
            assertThat(domain.weight()).isEqualTo(100);
            assertThat(domain.metadata()).containsEntry("zone", "a");
        }

        @Test
        void should_map_unrecognized_status_to_unknown_when_mapper_maps_enum() {
            // wire 未知值路径：消息字段 setter 收 raw int，getter 归一为 UNRECOGNIZED 哨兵
            org.mydotey.ai.artemis.proto.common.v1.Instance proto =
                    protoInstance("i1").toBuilder().setStatusValue(99).build();
            assertThat(proto.getStatus()).isEqualTo(org.mydotey.ai.artemis.proto.common.v1.InstanceStatus.UNRECOGNIZED);

            assertThat(mapper.toDomain(proto).status()).isEqualTo(InstanceStatus.UNKNOWN);
        }
    }

    @Nested
    class DomainToProto {

        @Test
        void should_map_response_fields_when_mapping_to_proto() {
            RegisterResponse response = new RegisterResponse(
                    new ResponseStatus(Ack.FAIL, new ErrorInfo(ErrorCodes.INVALID_ARGUMENT, "blank instanceId")),
                    new RegisterResponseData(List.of(new ServiceInstanceErrors(
                            new ServiceKey("default", "order-service"),
                            List.of(new InstanceError(
                                    new InstanceKey(new ServiceKey("default", "order-service"), ""),
                                    new ErrorInfo(ErrorCodes.INVALID_ARGUMENT, "blank instanceId")))))));

            org.mydotey.ai.artemis.proto.registry.v1.RegisterResponse proto = mapper.toProto(response);

            assertThat(proto.getStatus().getAck()).isEqualTo(org.mydotey.ai.artemis.proto.common.v1.Ack.ACK_FAIL);
            assertThat(proto.getStatus().getError().getCode()).isEqualTo("INVALID_ARGUMENT");
            assertThat(proto.getData().getServicesList()).singleElement().satisfies(group -> {
                assertThat(group.getKey().getService()).isEqualTo("order-service");
                assertThat(group.getFailedList())
                        .singleElement()
                        .satisfies(error ->
                                assertThat(error.getInstance().getInstanceId()).isEmpty());
            });
        }

        @Test
        void should_omit_error_when_ack_success() {
            RegisterResponse response =
                    new RegisterResponse(new ResponseStatus(Ack.SUCCESS, null), new RegisterResponseData(List.of()));

            org.mydotey.ai.artemis.proto.registry.v1.RegisterResponse proto = mapper.toProto(response);

            assertThat(proto.getStatus().getAck()).isEqualTo(org.mydotey.ai.artemis.proto.common.v1.Ack.ACK_SUCCESS);
            assertThat(proto.getStatus().hasError()).isFalse();
        }
    }

    @Nested
    class LayerGuard {

        @Test
        void should_keep_proto_types_out_of_domain_when_mapping() throws IOException {
            // 结构断言：domain 包的类签名（含字段/方法/构造器泛型）不出现 proto 生成类型
            Path domainDir = Path.of(Instance.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .getPath())
                    .resolve("org/mydotey/ai/artemis/registry/domain");
            try (Stream<Path> classes = Files.list(domainDir)) {
                List<Class<?>> domainClasses = classes.filter(
                                path -> path.toString().endsWith(".class"))
                        .map(path -> classNameOf(path, domainDir))
                        .map(LayerGuard::forName)
                        .toList();
                assertThat(domainClasses).isNotEmpty();

                for (Class<?> clazz : domainClasses) {
                    assertNoProto(clazz.getCanonicalName() == null ? clazz.getName() : clazz.getCanonicalName());
                    for (var field : clazz.getDeclaredFields()) {
                        assertNoProto(field.getGenericType().getTypeName());
                    }
                    for (Executable member : Stream.concat(
                                    Stream.of(clazz.getDeclaredConstructors()), Stream.of(clazz.getDeclaredMethods()))
                            .toList()) {
                        assertNoProto(member.toGenericString());
                    }
                }
            }
        }

        private static void assertNoProto(String signature) {
            assertThat(signature)
                    .as("domain 不得出现 proto 类型：%s", signature)
                    .doesNotContain("org.mydotey.ai.artemis.proto");
        }

        private static String classNameOf(Path classFile, Path domainDir) {
            String relative = domainDir.relativize(classFile).toString();
            return "org.mydotey.ai.artemis.registry.domain."
                    + relative.replace('/', '.').replaceAll("\\.class$", "");
        }

        private static Class<?> forName(String name) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // ---- 设施 ----

    private static org.mydotey.ai.artemis.proto.common.v1.ServiceKey.Builder protoKey() {
        return org.mydotey.ai.artemis.proto.common.v1.ServiceKey.newBuilder()
                .setNamespace("default")
                .setService("order-service");
    }

    private static org.mydotey.ai.artemis.proto.common.v1.Instance protoInstance(String instanceId) {
        return org.mydotey.ai.artemis.proto.common.v1.Instance.newBuilder()
                .setInstanceId(instanceId)
                .setHost("10.0.0.1")
                .setPort(8080)
                .setStatus(org.mydotey.ai.artemis.proto.common.v1.InstanceStatus.INSTANCE_STATUS_UP)
                .setWeight(100)
                .putMetadata("zone", "a")
                .build();
    }
}
