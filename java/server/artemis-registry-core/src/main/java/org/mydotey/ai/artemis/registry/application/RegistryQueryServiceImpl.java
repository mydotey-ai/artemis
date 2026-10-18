package org.mydotey.ai.artemis.registry.application;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.mydotey.ai.artemis.registry.domain.ConfigProperty;
import org.mydotey.ai.artemis.registry.domain.InstanceRegistry;
import org.mydotey.ai.artemis.registry.domain.LeaseDetail;
import org.mydotey.ai.artemis.registry.domain.LeaseStatus;
import org.mydotey.ai.artemis.registry.domain.SelfProtection;
import org.mydotey.ai.artemis.registry.domain.ServiceInstances;
import org.mydotey.ai.artemis.registry.domain.ServiceKey;
import org.mydotey.ai.artemis.registry.domain.ServiceSummary;
import org.mydotey.scf.ConfigurationManager;
import org.mydotey.scf.ConfigurationSource;
import org.mydotey.scf.Property;
import org.mydotey.scf.PropertyConfig;
import org.mydotey.scf.facade.StringProperties;

/**
 * 只读查询实现：读注册表共享快照 / 租约明细 / 保护统计 / SCF 配置。
 * 全部为弱一致读，不加锁；敏感配置值在本层脱敏，管理面不接触明文凭证。
 */
public final class RegistryQueryServiceImpl implements RegistryQueryService {

    private static final String MASKED = "******";
    /** 单次返回上限：约 1 MB，远低于 gRPC 默认 4 MiB 入站上限。 */
    private static final int MAX_LEASE_DETAILS = 10_000;

    private static final List<String> SENSITIVE_MARKERS =
            List.of("password", "passwd", "pwd", "secret", "token", "credential", "apikey", "accesskey", "privatekey");

    private static final Comparator<ServiceKey> SERVICE_ORDER =
            Comparator.comparing(ServiceKey::namespace).thenComparing(ServiceKey::service);
    private static final Comparator<LeaseDetail> LEASE_ORDER =
            Comparator.comparing(LeaseDetail::service, SERVICE_ORDER).thenComparing(LeaseDetail::instanceId);

    private final InstanceRegistry registry;
    private final SelfProtection protection;
    private final ConfigurationManager configurationManager;

    public RegistryQueryServiceImpl(InstanceRegistry registry, SelfProtection protection, StringProperties properties) {
        this.registry = registry;
        this.protection = protection;
        this.configurationManager = properties.getManager();
    }

    /** 弱一致读：注册进行中可能瞬时读到旧快照（AP 语义，排障面可接受）。 */
    @Override
    public List<ServiceSummary> listServices(String namespace) {
        boolean filter = namespace != null && !namespace.isBlank();
        return registry.snapshots().stream()
                .filter(service -> !filter || service.key().namespace().equals(namespace))
                .map(service ->
                        new ServiceSummary(service.key(), service.instances().size()))
                .sorted(Comparator.comparing(ServiceSummary::key, SERVICE_ORDER))
                .toList();
    }

    @Override
    public Optional<ServiceInstances> getInstances(ServiceKey key) {
        return key == null ? Optional.empty() : registry.snapshot(key);
    }

    @Override
    public LeaseStatus leaseStatus(String namespace, String service) {
        List<LeaseDetail> all = registry.leaseDetails().stream()
                .filter(lease -> matches(lease.service().namespace(), namespace))
                .filter(lease -> matches(lease.service().service(), service))
                .sorted(LEASE_ORDER)
                .toList();
        boolean truncated = all.size() > MAX_LEASE_DETAILS;
        List<LeaseDetail> leases = truncated ? all.subList(0, MAX_LEASE_DETAILS) : all;
        return new LeaseStatus(protection.stats(), leases, truncated);
    }

    @Override
    public List<ConfigProperty> configStatus() {
        return configurationManager.getProperties().stream()
                .map(RegistryQueryServiceImpl::toConfigProperty)
                .sorted(Comparator.comparing(ConfigProperty::key))
                .toList();
    }

    /** SCF facade 以 raw Property 暴露全量已创建配置项。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ConfigProperty toConfigProperty(Property property) {
        PropertyConfig config = property.getConfig();
        String key = String.valueOf(config.getKey());
        boolean sensitive = isSensitive(key);
        ConfigurationSource source = property.getSource();
        return new ConfigProperty(
                key,
                sensitive ? MASKED : stringValue(property.getValue()),
                source == null ? "" : source.getConfig().getName(),
                config.isStatic(),
                // 默认值在默认部署下就是生效凭证，必须同样脱敏
                sensitive ? MASKED : stringValue(config.getDefaultValue()),
                config.getDoc() == null ? "" : config.getDoc());
    }

    private static boolean matches(String value, String filter) {
        return filter == null || filter.isBlank() || value.equals(filter);
    }

    private static boolean isSensitive(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return SENSITIVE_MARKERS.stream().anyMatch(lower::contains);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
