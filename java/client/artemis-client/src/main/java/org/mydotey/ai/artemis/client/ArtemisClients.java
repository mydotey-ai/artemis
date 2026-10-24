package org.mydotey.ai.artemis.client;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.util.LinkedHashMap;
import java.util.Map;
import org.mydotey.ai.artemis.client.internal.DefaultDiscoveryClient;
import org.mydotey.ai.artemis.client.internal.DefaultRegistryClient;
import org.mydotey.ai.artemis.client.internal.RelaxedEnvironmentConfigurationSource;
import org.mydotey.scf.ConfigurationManager;
import org.mydotey.scf.ConfigurationSource;
import org.mydotey.scf.facade.ConfigurationManagers;
import org.mydotey.scf.facade.SimpleConfigurationSources;
import org.mydotey.scf.facade.StringProperties;

/**
 * SDK 入口工厂。全部配置经 SCF 键 {@code artemis.client.*}（单一来源）：
 * 地址/凭证/心跳/重连/重试键定义与默认值见各消费类；地址与凭证**使用时读取**（未静态固化）。
 * 运行时变更的生效时机由配置源语义决定：文件源重载/自定义源 raiseChangeEvent 后生效；
 * 缺省系统属性/环境变量为启动期源（不产生变更事件）。必配项
 * {@code artemis.client.registry.addresses} 缺失时创建即 fail-fast。
 *
 * <p>可观测（Micrometer 库模式，技术选型 #34）：{@code newRegistryClient(properties, meterRegistry)}
 * 注入宿主 registry；未注入挂 {@link Metrics#globalRegistry}（宿主 {@code Metrics.addRegistry}
 * 接管，不加则空转）。指标集：{@code artemis.client.operation.failure}（Counter，tag operation）、
 * {@code artemis.client.report.duration}（Timer，tag operation）、
 * {@code artemis.client.heartbeat.failure}（Counter）、{@code artemis.client.reconnect}（Counter）、
 * {@code artemis.client.connected}（Gauge 0/1）、{@code artemis.client.instances}（Gauge）。
 */
public final class ArtemisClients {

    private static final String MANAGER_NAME = "artemis-client";

    private ArtemisClients() {}

    /** 以缺省配置源创建（系统属性 &gt; 环境变量），指标挂 globalRegistry。 */
    public static RegistryClient newRegistryClient() {
        return newRegistryClient(defaultProperties(), Metrics.globalRegistry);
    }

    /** 以宿主注入的 SCF properties 创建（可含文件源），指标挂 globalRegistry。 */
    public static RegistryClient newRegistryClient(StringProperties properties) {
        return newRegistryClient(properties, Metrics.globalRegistry);
    }

    /** 完整创建：SCF properties + Micrometer registry（starter 注入 Boot MeterRegistry；缺省 globalRegistry）。 */
    public static RegistryClient newRegistryClient(StringProperties properties, MeterRegistry meterRegistry) {
        return new DefaultRegistryClient(properties, meterRegistry);
    }

    /** 以缺省配置源创建发现客户端（系统属性 &gt; 环境变量），指标挂 globalRegistry。 */
    public static DiscoveryClient newDiscoveryClient() {
        return newDiscoveryClient(defaultProperties(), Metrics.globalRegistry);
    }

    /** 以宿主注入的 SCF properties 创建发现客户端，指标挂 globalRegistry。 */
    public static DiscoveryClient newDiscoveryClient(StringProperties properties) {
        return newDiscoveryClient(properties, Metrics.globalRegistry);
    }

    /** 完整创建发现客户端：SCF properties + Micrometer registry（缺省 globalRegistry）。 */
    public static DiscoveryClient newDiscoveryClient(StringProperties properties, MeterRegistry meterRegistry) {
        return new DefaultDiscoveryClient(properties, meterRegistry);
    }

    private static StringProperties defaultProperties() {
        Map<Integer, ConfigurationSource> sources = new LinkedHashMap<>();
        // 容器友好映射：artemis.client.registry.addresses → ARTEMIS_CLIENT_REGISTRY_ADDRESSES
        sources.put(2, new RelaxedEnvironmentConfigurationSource());
        sources.put(3, SimpleConfigurationSources.newSystemPropertiesSource());
        ConfigurationManager manager = ConfigurationManagers.newManager(MANAGER_NAME, sources);
        return new StringProperties(manager);
    }
}
