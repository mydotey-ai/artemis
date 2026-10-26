package org.mydotey.ai.artemis.dist;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.mydotey.ai.artemis.common.config.RelaxedEnvironmentConfigurationSource;
import org.mydotey.ai.artemis.common.config.ReloadablePropertiesFileConfigurationSource;
import org.mydotey.ai.artemis.common.config.ScfProperties;
import org.mydotey.scf.ConfigurationManager;
import org.mydotey.scf.ConfigurationSource;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.ConfigurationManagers;
import org.mydotey.scf.facade.SimpleConfigurationSources;
import org.mydotey.scf.facade.StringProperties;

/**
 * 组合期端口配置（SCF，静态键 {@code artemis.dist.*}）。在两个 Spring 上下文启动**之前**读取，
 * 故 source 不含 Spring {@code Environment}：优先级系统属性 3 &gt; 环境变量 2 &gt; 工作目录
 * {@code artemis.properties} 1（同服务端 source 纪律，去掉 Spring source 层）。
 *
 * <p>与端口注入路径区分：本类只决定「launcher 注入哪些端口」；各上下文内的业务配置仍走
 * 各自单服务的 SCF 装配（可共享同一工作目录 {@code artemis.properties}，键各取所需）。
 * 非法值 fail-fast（显式拒绝，不静默回落默认——组合端口错配会让 all-in-one 起在错误端口上）。
 */
public final class AllInOneConfig {

    static final String CONFIG_FILE_NAME = "artemis.properties";
    static final String MANAGER_NAME = "artemis-dist";
    static final String PREFIX = "artemis.dist";

    private AllInOneConfig() {}

    /**
     * 单次构建 SCF manager 一次性读出组合期输入（10 个端口 + 宿主已配置的 discovery 节点目录），
     * 避免逐键重复建 manager/source 链（ReloadablePropertiesFileConfigurationSource 每 manager
     * 一个 watcher 线程，重复创建即泄漏）。
     */
    public static AllInOneOptions options() {
        StringProperties properties = new StringProperties(manager());
        return AllInOneOptions.builder()
                .registryGrpcPort(port(properties, "registry.grpcPort", AllInOneOptions.DEFAULT_REGISTRY_GRPC_PORT))
                .registryHttpPort(port(properties, "registry.httpPort", AllInOneOptions.DEFAULT_REGISTRY_HTTP_PORT))
                .registryManagementPort(
                        port(properties, "registry.managementPort", AllInOneOptions.DEFAULT_REGISTRY_MANAGEMENT_PORT))
                .registryPeerPort(port(properties, "registry.peerPort", AllInOneOptions.DEFAULT_REGISTRY_PEER_PORT))
                .managementGrpcPort(
                        port(properties, "management.grpcPort", AllInOneOptions.DEFAULT_MANAGEMENT_GRPC_PORT))
                .managementHttpPort(
                        port(properties, "management.httpPort", AllInOneOptions.DEFAULT_MANAGEMENT_HTTP_PORT))
                .managementManagementPort(port(
                        properties, "management.managementPort", AllInOneOptions.DEFAULT_MANAGEMENT_MANAGEMENT_PORT))
                .discoveryGrpcPort(port(properties, "discovery.grpcPort", AllInOneOptions.DEFAULT_DISCOVERY_GRPC_PORT))
                .discoveryHttpPort(port(properties, "discovery.httpPort", AllInOneOptions.DEFAULT_DISCOVERY_HTTP_PORT))
                .discoveryManagementPort(
                        port(properties, "discovery.managementPort", AllInOneOptions.DEFAULT_DISCOVERY_MANAGEMENT_PORT))
                .configuredDiscoveryNodes(ScfProperties.staticString(
                                properties,
                                "artemis.discovery.nodes",
                                null,
                                "discovery node directory (launcher injects loopback default only when unset)")
                        .getValue())
                .build();
    }

    private static ConfigurationManager manager() {
        Map<Integer, ConfigurationSource> sources = new LinkedHashMap<>();
        Path configFile = Path.of(CONFIG_FILE_NAME).toAbsolutePath();
        if (Files.exists(configFile)) {
            sources.put(1, new ReloadablePropertiesFileConfigurationSource(configFile));
        }
        sources.put(2, new RelaxedEnvironmentConfigurationSource());
        sources.put(3, SimpleConfigurationSources.newSystemPropertiesSource());
        return ConfigurationManagers.newManager(MANAGER_NAME, sources);
    }

    private static int port(StringProperties properties, String keySuffix, int defaultValue) {
        String key = PREFIX + "." + keySuffix;
        Property<String, String> raw = ScfProperties.staticString(
                properties, key, null, "all-in-one composed port (static, 1..65535; see docs/api/all-in-one.md)");
        String value = raw.getValue();
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        int port;
        try {
            port = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            // 非数字归入统一的非法值拒绝，不区分错误形态
            throw invalid(key, value);
        }
        if (port < 1 || port > 65535) {
            throw invalid(key, value);
        }
        return port;
    }

    private static IllegalArgumentException invalid(String key, String value) {
        return new IllegalArgumentException(key + " must be an integer in 1..65535: '" + value + "'");
    }
}
