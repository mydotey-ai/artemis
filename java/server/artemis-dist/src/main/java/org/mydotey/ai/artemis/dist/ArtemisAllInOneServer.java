package org.mydotey.ai.artemis.dist;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.discovery.server.ArtemisDiscoveryServer;
import org.mydotey.ai.artemis.registry.server.ArtemisRegistryServer;
import org.mydotey.ai.artemis.registry.server.peer.PeerServerConfig;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;

/**
 * all-in-one 入口：同 JVM 起 registry + discovery 两个独立 Spring 上下文，彼此仍走网络协议
 * （ADR-001：不做进程内直连优化），端口全部独立。两个上下文经各自 {@code spring.config.name}
 * 分隔配置文件（{@code artemis-all-in-one-registry/discovery.properties}，不含端口与上游集群），
 * 端口与上游集群由 launcher 以命令行参数注入——命令行优先级最高，标准的 {@code SPRING_*} 共享键
 * 不用于 all-in-one 覆盖（会同时命中两个上下文）。
 *
 * <p>启动时序：registry 先起（空表即 ready）→ 读 {@link PeerServerConfig#port()} 实际值 →
 * discovery 起（上游固定同进程 registry，首轮 bootstrap 空表即 SYNCED，冷启动无互相等待）。
 * discovery 启动失败时回滚已起的 registry 上下文，不留半启进程。关闭序 discovery → registry（幂等）。
 */
@Slf4j
public final class ArtemisAllInOneServer {

    /** registry 内部 peer 端口的第二 gRPC lifecycle bean 名（组合期排除后取主端口）。 */
    static final String PEER_LIFECYCLE_BEAN = "peerGrpcServerLifecycle";

    private ArtemisAllInOneServer() {}

    /** 生产入口：组合端口由 {@link AllInOneConfig}（SCF {@code artemis.dist.*}）提供。 */
    public static void main(String[] args) {
        if (args.length > 0) {
            // 端口/上下文隔离要求 launcher 独占命令行注入位，不转发宿主 args（会同时命中两个上下文）；
            // 显式提示而非静默吞掉（配置出口是 artemis.dist.* SCF 键与工作目录 artemis.properties）
            log.warn(
                    "command-line args are not forwarded (ports/context isolation); "
                            + "use artemis.dist.* keys or working-dir artemis.properties instead, ignored: {}",
                    (Object) args);
        }
        Node node = start(AllInOneConfig.options());
        log.info(
                "artemis all-in-one started: registry grpc={} http={} peer={}, discovery grpc={} http={}",
                node.registryGrpcPort(),
                node.registryHttpPort(),
                node.registryPeerPort(),
                node.discoveryGrpcPort(),
                node.discoveryHttpPort());
    }

    /** 组合启动（测试/嵌入用）：registry 先起，读实际 peer 端口后起 discovery；失败时回滚已起上下文。 */
    public static Node start(AllInOneOptions options) {
        validate(options);
        ConfigurableApplicationContext registry = new SpringApplicationBuilder(ArtemisRegistryServer.class)
                .registerShutdownHook(false)
                .run(registryArgs(options).toArray(String[]::new));
        ConfigurableApplicationContext discovery;
        try {
            int peerPort = registry.getBean(PeerServerConfig.class).port();
            discovery = new SpringApplicationBuilder(ArtemisDiscoveryServer.class)
                    .registerShutdownHook(false)
                    .run(discoveryArgs(options, peerPort).toArray(String[]::new));
        } catch (RuntimeException | Error e) {
            closeQuietly(registry, e);
            throw e;
        }
        Node node = new Node(registry, discovery);
        // 单个 JVM shutdown hook 统一关停（两上下文自身 hook 已关）；Node.close() 中移除，
        // 嵌入式反复 start/close 不积累 hook
        Runtime.getRuntime().addShutdownHook(node.shutdownHook());
        return node;
    }

    /** 回滚关闭：关闭失败只作 suppressed 附加，不掩盖原始启动异常。 */
    private static void closeQuietly(ConfigurableApplicationContext context, Throwable cause) {
        try {
            context.close();
        } catch (RuntimeException | Error closeFailure) {
            cause.addSuppressed(closeFailure);
        }
    }

    private static void validate(AllInOneOptions options) {
        check("registryGrpcPort", options.getRegistryGrpcPort());
        check("registryHttpPort", options.getRegistryHttpPort());
        check("registryManagementPort", options.getRegistryManagementPort());
        // peer 端口产品语义为固定端口（1..65535，不支持 0 随机）
        if (options.getRegistryPeerPort() < 1 || options.getRegistryPeerPort() > 65535) {
            throw new IllegalArgumentException(
                    "registryPeerPort must be in 1..65535: " + options.getRegistryPeerPort());
        }
        check("discoveryGrpcPort", options.getDiscoveryGrpcPort());
        check("discoveryHttpPort", options.getDiscoveryHttpPort());
        check("discoveryManagementPort", options.getDiscoveryManagementPort());
    }

    private static void check(String name, int port) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException(name + " must be in 0..65535 (0 = random): " + port);
        }
    }

    private static List<String> registryArgs(AllInOneOptions options) {
        return List.of(
                "--spring.config.name=artemis-all-in-one-registry",
                "--spring.grpc.server.port=" + options.getRegistryGrpcPort(),
                "--server.port=" + options.getRegistryHttpPort(),
                "--management.server.port=" + options.getRegistryManagementPort(),
                // all-in-one 的 registry 内部端口只被同进程 discovery 消费，绑 loopback 即可
                "--artemis.registry.internal.port=" + options.getRegistryPeerPort(),
                "--artemis.registry.internal.address=127.0.0.1");
    }

    private static List<String> discoveryArgs(AllInOneOptions options, int peerPort) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.config.name=artemis-all-in-one-discovery",
                "--spring.grpc.server.port=" + options.getDiscoveryGrpcPort(),
                "--server.port=" + options.getDiscoveryHttpPort(),
                "--management.server.port=" + options.getDiscoveryManagementPort(),
                // 上游固定同进程 registry（命令行注入，优先于工作目录 artemis.properties 同名键）；
                // all-in-one 不提供多集群聚合（D9-5，多集群 union 由独立 discovery 部署承载）
                "--artemis.discovery.registry.clusters={\"default\":\"127.0.0.1:" + peerPort + "\"}"));
        // 节点目录默认指向本 discovery 外部端口（SDK 前置寻址）：仅宿主未配置时注入，显式配置优先
        // （判断范围 = 组合期 SCF source 链：系统属性/环境变量/工作目录 artemis.properties，all-in-one
        // 的配置出口纪律；Spring 途径的业务键不在支持面）；0（随机，测试用）时无法自引用，跳过
        String configuredNodes = options.getConfiguredDiscoveryNodes();
        if ((configuredNodes == null || configuredNodes.isBlank()) && options.getDiscoveryGrpcPort() > 0) {
            args.add("--artemis.discovery.nodes=127.0.0.1:" + options.getDiscoveryGrpcPort());
        }
        return args;
    }

    /** 双上下文句柄：暴露实际端口；close 幂等并按 discovery → registry 顺序关闭。 */
    public static final class Node implements AutoCloseable {

        private final ConfigurableApplicationContext registry;

        private final ConfigurableApplicationContext discovery;

        private final AtomicBoolean closed = new AtomicBoolean();

        private final Thread shutdownHook;

        Node(ConfigurableApplicationContext registry, ConfigurableApplicationContext discovery) {
            this.registry = registry;
            this.discovery = discovery;
            this.shutdownHook = new Thread(this::close, "artemis-all-in-one-shutdown");
        }

        Thread shutdownHook() {
            return shutdownHook;
        }

        public ConfigurableApplicationContext registry() {
            return registry;
        }

        public ConfigurableApplicationContext discovery() {
            return discovery;
        }

        public int registryGrpcPort() {
            return grpcPort(registry);
        }

        public int registryHttpPort() {
            return httpPort(registry);
        }

        public int registryPeerPort() {
            return registry.getBean(PeerServerConfig.class).port();
        }

        public int discoveryGrpcPort() {
            return grpcPort(discovery);
        }

        public int discoveryHttpPort() {
            return httpPort(discovery);
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException e) {
                // JVM 已在 shutdown 中：hook 由运行时统一触发，无需移除
            }
            // discovery 先关（消费方停流），失败不阻断 registry 关停（快照/排水仍须完成）；异常带回
            RuntimeException failure = null;
            try {
                discovery.close();
            } catch (RuntimeException e) {
                failure = e;
            }
            try {
                registry.close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private static int grpcPort(ConfigurableApplicationContext context) {
            // registry 上下文有两个 gRPC lifecycle（主端口 + peer），排除 peer 取主；discovery 只有一个
            Map<String, GrpcServerLifecycle> lifecycles = context.getBeansOfType(GrpcServerLifecycle.class);
            int[] ports = lifecycles.entrySet().stream()
                    .filter(entry -> !PEER_LIFECYCLE_BEAN.equals(entry.getKey()))
                    .mapToInt(entry -> entry.getValue().getPort())
                    .toArray();
            if (ports.length != 1) {
                throw new IllegalStateException("expected exactly one main grpc server lifecycle, got " + ports.length);
            }
            return ports[0];
        }

        private static int httpPort(ConfigurableApplicationContext context) {
            return ((WebServerApplicationContext) context).getWebServer().getPort();
        }
    }
}
