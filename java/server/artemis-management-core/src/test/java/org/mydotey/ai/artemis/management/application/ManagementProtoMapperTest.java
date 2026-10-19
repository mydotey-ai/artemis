package org.mydotey.ai.artemis.management.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.management.domain.InstanceStatus;
import org.mydotey.ai.artemis.management.domain.ServiceKey;
import org.mydotey.ai.artemis.proto.common.v1.Instance;
import org.mydotey.ai.artemis.proto.management.v1.ConfigProperty;
import org.mydotey.ai.artemis.proto.management.v1.GetLeaseStatusResponseData;
import org.mydotey.ai.artemis.proto.management.v1.LeaseDetail;
import org.mydotey.ai.artemis.proto.management.v1.SelfProtectionStats;

class ManagementProtoMapperTest {

    private final ManagementProtoMapper mapper = ManagementProtoMapper.INSTANCE;

    @Test
    void should_map_instance_and_status_when_converting_proto() {
        Instance proto = Instance.newBuilder()
                .setInstanceId("i1")
                .setHost("10.0.0.1")
                .setPort(8080)
                .setStatus(org.mydotey.ai.artemis.proto.common.v1.InstanceStatus.INSTANCE_STATUS_UNSPECIFIED)
                .setWeight(100)
                .putMetadata("group", "g1")
                .build();

        var domain = mapper.toDomain(proto);

        assertThat(domain.instanceId()).isEqualTo("i1");
        assertThat(domain.host()).isEqualTo("10.0.0.1");
        assertThat(domain.port()).isEqualTo(8080);
        assertThat(domain.status()).isEqualTo(InstanceStatus.UP);
        assertThat(domain.weight()).isEqualTo(100);
        assertThat(domain.metadata()).containsEntry("group", "g1");
        assertThat(mapper.toDomain(Instance.newBuilder()
                                .setStatus(org.mydotey.ai.artemis.proto.common.v1.InstanceStatus.INSTANCE_STATUS_DOWN)
                                .build())
                        .status())
                .isEqualTo(InstanceStatus.DOWN);
    }

    @Test
    void should_map_service_key_to_proto_when_querying() {
        var proto = mapper.toProto(new ServiceKey("ns", "svc"));

        assertThat(proto.getNamespace()).isEqualTo("ns");
        assertThat(proto.getService()).isEqualTo("svc");
    }

    @Test
    void should_map_lease_and_config_when_converting_proto() {
        GetLeaseStatusResponseData data = GetLeaseStatusResponseData.newBuilder()
                .setProtection(SelfProtectionStats.newBuilder()
                        .setWindowRenewals(10)
                        .setMaxCount(3)
                        .setProtecting(true))
                .addLeases(LeaseDetail.newBuilder()
                        .setService(org.mydotey.ai.artemis.proto.common.v1.ServiceKey.newBuilder()
                                .setNamespace("ns")
                                .setService("svc"))
                        .setInstanceId("i1")
                        .setAgeMs(400)
                        .setRemainingTtlMs(600))
                .setTruncated(true)
                .build();

        var status = mapper.toDomain(data);

        assertThat(status.protection().windowRenewals()).isEqualTo(10);
        assertThat(status.protection().maxCount()).isEqualTo(3);
        assertThat(status.protection().protecting()).isTrue();
        assertThat(status.leases()).singleElement().satisfies(lease -> {
            assertThat(lease.service()).isEqualTo(new ServiceKey("ns", "svc"));
            assertThat(lease.instanceId()).isEqualTo("i1");
            assertThat(lease.ageMs()).isEqualTo(400);
            assertThat(lease.remainingTtlMs()).isEqualTo(600);
        });
        assertThat(status.truncated()).isTrue();

        var property = mapper.toDomain(ConfigProperty.newBuilder()
                .setKey("artemis.registry.auth.password")
                .setValue("******")
                .setSource("springEnvironment")
                .setIsStatic(true)
                .setDefaultValue("******")
                .setDoc("auth password")
                .build());
        assertThat(property.key()).isEqualTo("artemis.registry.auth.password");
        assertThat(property.value()).isEqualTo("******");
        assertThat(property.isStatic()).isTrue();
        assertThat(property.defaultValue()).isEqualTo("******");
    }
}
