package org.mydotey.ai.artemis.registry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.common.config.ScfProperties;
import org.mydotey.ai.artemis.registry.MutableClock;
import org.mydotey.ai.artemis.registry.TestProperties;
import org.mydotey.ai.artemis.registry.domain.ConfigProperty;
import org.mydotey.ai.artemis.registry.domain.Instance;
import org.mydotey.ai.artemis.registry.domain.InstanceRegistry;
import org.mydotey.ai.artemis.registry.domain.InstanceStatus;
import org.mydotey.ai.artemis.registry.domain.LeaseDetail;
import org.mydotey.ai.artemis.registry.domain.LeaseStatus;
import org.mydotey.ai.artemis.registry.domain.SelfProtection;
import org.mydotey.ai.artemis.registry.domain.ServiceKey;
import org.mydotey.ai.artemis.registry.domain.ServiceSummary;
import org.mydotey.scf.facade.StringProperties;

/** 内部只读查询：服务列表/实例快照/租约明细/保护统计/配置状态（含脱敏与排序）。 */
class RegistryQueryServiceImplTest {

    private static final ServiceKey KEY = new ServiceKey("default", "order-service");

    private MutableClock clock;
    private TestProperties properties;
    private InstanceRegistry registry;
    private SelfProtection protection;
    private RegistryQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        properties =
                TestProperties.of(Map.of(InstanceRegistry.KEY_LEASE_TTL_MS, "30000", "test.dynamic", "from-source"));
        StringProperties props = properties.properties();
        registry = new InstanceRegistry(props, clock, change -> {});
        protection = new SelfProtection(props, new SimpleMeterRegistry(), clock);
        service = new RegistryQueryServiceImpl(registry, protection, props);
    }

    @Test
    void should_list_services_when_registered() {
        registry.register(new ServiceKey("default", "svc-b"), List.of(instance("i1")));
        registry.register(new ServiceKey("default", "svc-a"), List.of(instance("i1"), instance("i2")));
        registry.register(new ServiceKey("other", "svc-c"), List.of(instance("i1")));

        assertThat(service.listServices(null))
                .extracting(ServiceSummary::key, ServiceSummary::instanceCount)
                .containsExactly(
                        tuple(new ServiceKey("default", "svc-a"), 2),
                        tuple(new ServiceKey("default", "svc-b"), 1),
                        tuple(new ServiceKey("other", "svc-c"), 1));
    }

    @Test
    void should_filter_by_namespace_when_listing_services() {
        registry.register(new ServiceKey("default", "svc-a"), List.of(instance("i1")));
        registry.register(new ServiceKey("other", "svc-c"), List.of(instance("i1")));

        assertThat(service.listServices("other"))
                .extracting(ServiceSummary::key)
                .containsExactly(new ServiceKey("other", "svc-c"));
        assertThat(service.listServices("  ")).hasSize(2);
    }

    @Test
    void should_return_empty_when_service_not_registered() {
        assertThat(service.getInstances(new ServiceKey("default", "ghost"))).isEmpty();
        assertThat(service.listServices("missing")).isEmpty();
    }

    @Test
    void should_report_lease_age_and_remaining_ttl_when_lease_status() {
        registry.register(KEY, List.of(instance("i1")));

        clock.advance(Duration.ofSeconds(10));

        assertThat(service.leaseStatus(null, null).leases())
                .extracting(
                        LeaseDetail::service, LeaseDetail::instanceId, LeaseDetail::ageMs, LeaseDetail::remainingTtlMs)
                .containsExactly(tuple(KEY, "i1", 10_000L, 20_000L));
    }

    @Test
    void should_sort_leases_stably() {
        registry.register(new ServiceKey("default", "svc-b"), List.of(instance("i2"), instance("i1")));
        registry.register(new ServiceKey("default", "svc-a"), List.of(instance("i3")));

        assertThat(service.leaseStatus(null, null).leases())
                .extracting(detail -> detail.service().service(), LeaseDetail::instanceId)
                .containsExactly(tuple("svc-a", "i3"), tuple("svc-b", "i1"), tuple("svc-b", "i2"));
    }

    @Test
    void should_include_protection_stats_when_lease_status() {
        protection.recordRenewals(120);

        LeaseStatus status = service.leaseStatus(null, null);

        assertThat(status.protection().windowRenewals()).isEqualTo(120);
        assertThat(status.protection().maxCount()).isEqualTo(120);
    }

    @Test
    void should_filter_leases_when_namespace_or_service_given() {
        registry.register(new ServiceKey("a", "svc"), List.of(instance("i1")));
        registry.register(new ServiceKey("b", "svc"), List.of(instance("i2")));

        assertThat(service.leaseStatus("b", null).leases())
                .extracting(detail -> detail.service().namespace())
                .containsExactly("b");
        assertThat(service.leaseStatus(null, "svc").leases()).hasSize(2);
        assertThat(service.leaseStatus("b", "other").leases()).isEmpty();
    }

    @Test
    void should_truncate_when_lease_details_exceed_limit() {
        registry.register(
                KEY, IntStream.range(0, 10_001).mapToObj(i -> instance("i" + i)).toList());

        LeaseStatus status = service.leaseStatus(null, null);

        assertThat(status.truncated()).isTrue();
        assertThat(status.leases()).hasSize(10_000);
    }

    @Test
    void should_report_source_static_flag_and_sort_when_config_status() {
        StringProperties props = properties.properties();
        props.getStringProperty("test.dynamic", "default-b");
        ScfProperties.staticString(props, "test.static", "default-a", "static doc");

        List<ConfigProperty> config = service.configStatus();

        assertThat(config).extracting(ConfigProperty::key).isSorted();
        assertThat(config)
                .filteredOn(property -> property.key().startsWith("test."))
                .extracting(
                        ConfigProperty::key,
                        ConfigProperty::isStatic,
                        ConfigProperty::defaultValue,
                        ConfigProperty::doc)
                .containsExactly(
                        tuple("test.dynamic", false, "default-b", ""),
                        tuple("test.static", true, "default-a", "static doc"));
        assertThat(config)
                .filteredOn(property -> property.key().equals("test.dynamic"))
                .singleElement()
                .satisfies(property -> assertThat(property.source()).isNotBlank());
        assertThat(config)
                .filteredOn(property -> property.key().equals("test.static"))
                .singleElement()
                .satisfies(property -> assertThat(property.source()).isEmpty());
    }

    @Test
    void should_mask_sensitive_config_when_config_status() {
        StringProperties props = properties.properties();
        ScfProperties.staticString(props, "test.password", "plain-default", "password doc");
        ScfProperties.staticString(props, "test.accessKey", "access-default", "access key doc");
        properties.set("test.password", "plain");
        properties.set("test.accessKey", "plain-access");

        List<ConfigProperty> config = service.configStatus();

        assertThat(config)
                .filteredOn(property -> property.key().equals("test.password"))
                .singleElement()
                .satisfies(property -> {
                    // 默认值在默认部署下就是生效凭证，同样脱敏
                    assertThat(property.value()).isEqualTo("******");
                    assertThat(property.defaultValue()).isEqualTo("******");
                });
        assertThat(config)
                .filteredOn(property -> property.key().equals("test.accessKey"))
                .singleElement()
                .satisfies(property -> assertThat(property.value()).isEqualTo("******"));
    }

    private static Instance instance(String instanceId) {
        return new Instance(instanceId, "10.0.0.1", 8080, InstanceStatus.UP, 100, Map.of("zone", "a"));
    }
}
