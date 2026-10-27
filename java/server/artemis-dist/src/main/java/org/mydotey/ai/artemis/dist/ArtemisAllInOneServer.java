package org.mydotey.ai.artemis.dist;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.discovery.server.ArtemisDiscoveryServer;
import org.mydotey.ai.artemis.management.server.ArtemisManagementServer;
import org.mydotey.ai.artemis.registry.server.ArtemisRegistryServer;
import org.mydotey.ai.artemis.registry.server.peer.PeerServerConfig;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;

/**
 * all-in-one 入口：同 JVM 起 registry + management + discovery 三个独立 Spring 上下文，彼此仍走
 * 网络协议（ADR-001：不做进程内直连优化），端口全部独立。三个上下文经各自 {@code spring.config.name}
 * 分隔配置文件（{@code artemis-all-in-one-registry/management/discovery.properties}，不含端口与
 * 互连端点），端口与互连由 launcher 以命令行参数注入——命令行优先级最高，标准的 {@code SPRING_*}
 * 共享键不用于 all-in-one 覆盖（会同时命中多个上下文）。
 *
 * <p>启动时序 registry → management → discovery（依赖反序）：registry 先起（空表即 ready）→
 * 读 {@link PeerServerConfig#port()} 实际值 → management 起（事实查询上游固定同进程 registry 内部
 * 端口）→ 读 management gRPC 实际端口 → discovery 起（上游固定同进程 registry、规则流固定同进程
 * management，治理链零等待闭合）。任一后续上下文启动失败按启动反序回滚已起上下文，不留半启进程。
 * 关闭序 discovery → management → registry（依赖反序，幂等）。
 */
@Slf4j
public final class ArtemisAllInOneServer {

    /** registry 内部 peer 端口的第二 gRPC lifecycle bean 名（组合期排除后取主端口）。 */
    static final String PEER_LIFECYCLE_BEAN = "peerGrpcServerLifecycle";

    private ArtemisAllInOneServer() {}

    /** 生产入口：组合端口由 {@link AllInOneConfig}（SCF {@code artemis.dist.*}）提供。 */
    public static void main(String[] args) {
        if (args.length > 0) {
            // 端口/上下文隔离要求 launcher 独占命令行注入位，不转发宿主 args（会同时命中多个上下文）；
            // 显式提示而非静默吞掉（配置出口是 artemis.dist.* SCF 键与工作目录 artemis.properties）
            log.warn(
                    "command-line args are not forwarded (ports/context isolation); "
                            + "use artemis.dist.* keys or working-dir artemis.properties instead, ignored: {}",
                    (Object) args);
        }
        Node node = start(AllInOneConfig.options());
        log.info(
                "artemis all-in-one started: registry grpc={} http={} peer={}, management grpc={} http={}, "
                        + "discovery grpc={} http={}",
                node.registryGrpcPort(),
                node.registryHttpPort(),
                node.registryPeerPort(),
                node.managementGrpcPort(),
                node.managementHttpPort(),
                node.discoveryGrpcPort(),
                node.discoveryHttpPort());
    }

    /**
     * 组合启动（测试/嵌入用）：registry → management → discovery 依序起，互连端点读实际值注入；
     * 失败按启动反序回滚已起上下文。
     */
    public static Node start(AllInOneOptions options) {
        validate(options);
        List<ConfigurableApplicationContext> started = new ArrayList<>();
        try {
            ConfigurableApplicationContext registry = new SpringApplicationBuilder(ArtemisRegistryServer.class)
                    .registerShutdownHook(false)
                    .run(registryArgs(options).toArray(String[]::new));
            started.add(registry);
            int peerPort = registry.getBean(PeerServerConfig.class).port();
            ConfigurableApplicationContext management = new SpringApplicationBuilder(ArtemisManagementServer.class)
                    .registerShutdownHook(false)
                    .run(managementArgs(options, peerPort).toArray(String[]::new));
            started.add(management);
            ConfigurableApplicationContext discovery = new SpringApplicationBuilder(ArtemisDiscoveryServer.class)
                    .registerShutdownHook(false)
                    .run(discoveryArgs(options, peerPort, grpcPort(management)).toArray(String[]::new));
            started.add(discovery);
            Node node = new Node(registry, management, discovery);
            // 单个 JVM shutdown hook 统一关停（三上下文自身 hook 已关）；Node.close() 中移除，
            // 嵌入式反复 start/close 不积累 hook
            Runtime.getRuntime().addShutdownHook(node.shutdownHook());
            return node;
        } catch (RuntimeException | Error e) {
            // 启动反序回滚（discovery 失败 → management、registry；management 失败 → registry）
            for (int i = started.size() - 1; i >= 0; i--) {
                closeQuietly(started.get(i), e);
            }
            throw e;
        }
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
        check("managementGrpcPort", options.getManagementGrpcPort());
        check("managementHttpPort", options.getManagementHttpPort());
        check("managementManagementPort", options.getManagementManagementPort());
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
        List<String> args = new ArrayList<>(List.of(
                "--spring.config.name=artemis-all-in-one-registry",
                "--spring.grpc.server.port=" + options.getRegistryGrpcPort(),
                "--server.port=" + options.getRegistryHttpPort(),
                "--management.server.port=" + options.getRegistryManagementPort(),
                // all-in-one 的 registry 内部端口只被同进程 management/discovery 消费，绑 loopback 即可
                "--artemis.registry.internal.port=" + options.getRegistryPeerPort(),
                "--artemis.registry.internal.address=127.0.0.1"));
        // 单成员自报（phase-3 部署形态缺口补全）：未配置 members 时 /cluster/nodes 无 external 端点，
        // SDK 前置寻址得到空节点列表无法接入（单节点 = 1 成员集群，无特殊分支）；显式配置优先，
        // 外部端点默认 loopback（eval/VM 同机），跨机接入经 env/properties 覆盖 members。
        // grpc 端口 0（随机，测试/嵌入直构）无确定端点可自报，不注入（含 self——成员表为空时
        // self 必须缺省，成对注入成对跳过）
        String configuredMembers = options.getConfiguredClusterMembers();
        String configuredSelf = options.getConfiguredClusterSelf();
        if (options.getRegistryGrpcPort() > 0) {
            if (configuredMembers == null || configuredMembers.isBlank()) {
                args.add("--artemis.registry.cluster.members=all-in-one@127.0.0.1:" + options.getRegistryPeerPort()
                        + "@127.0.0.1:" + options.getRegistryGrpcPort());
            }
            // self 默认独立注入（显式配置优先）：覆盖 members 只改端点的场景免配第二键（成员表非空时
            // self 必填，缺省即启动失败）；自定义 members 不含 all-in-one 时 fail-fast 报 nodeId 不匹配
            if (configuredSelf == null || configuredSelf.isBlank()) {
                args.add("--artemis.registry.cluster.self=all-in-one");
            }
        }
        return args;
    }

    private static List<String> managementArgs(AllInOneOptions options, int peerPort) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.config.name=artemis-all-in-one-management",
                // gRPC 监听双键：spring.grpc.server.port 是实际监听位，artemis.management.grpc.port 是
                // 条件装配开关（单服务默认 -1 不监听；all-in-one 治理链闭环前提 = 必监听）
                "--spring.grpc.server.port=" + options.getManagementGrpcPort(),
                "--artemis.management.grpc.port=" + options.getManagementGrpcPort(),
                "--server.port=" + options.getManagementHttpPort(),
                "--management.server.port=" + options.getManagementManagementPort(),
                // 事实查询上游固定同进程 registry 内部端口（RegistryQueryService 驻留端口，loopback）
                "--artemis.management.registry.endpoint=127.0.0.1:" + peerPort));
        // 订阅关系视图数据源（phase-5）：固定同进程 discovery 外部 HTTP 端口（loopback）；
        // 0（随机，测试用）时无法自引用，跳过注入（同下方 discovery.nodes 先例）
        if (options.getDiscoveryHttpPort() > 0) {
            args.add("--artemis.management.discovery.endpoints=127.0.0.1:" + options.getDiscoveryHttpPort());
        }
        return args;
    }

    private static List<String> discoveryArgs(AllInOneOptions options, int peerPort, int managementGrpcPort) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.config.name=artemis-all-in-one-discovery",
                "--spring.grpc.server.port=" + options.getDiscoveryGrpcPort(),
                "--server.port=" + options.getDiscoveryHttpPort(),
                "--management.server.port=" + options.getDiscoveryManagementPort(),
                // 上游固定同进程 registry（命令行注入，优先于工作目录 artemis.properties 同名键）；
                // all-in-one 不提供多集群聚合（D9-5，多集群 union 由独立 discovery 部署承载）
                "--artemis.discovery.registry.clusters={\"default\":\"127.0.0.1:" + peerPort + "\"}",
                // 治理链固定接通同进程 management（规则流 + 逻辑实例流；D7-2，完整形态默认闭合）
                "--artemis.discovery.management.endpoints=127.0.0.1:" + managementGrpcPort));
        // 节点目录默认指向本 discovery 外部端口（SDK 前置寻址）：仅宿主未配置时注入，显式配置优先
        // （判断范围 = 组合期 SCF source 链：系统属性/环境变量/工作目录 artemis.properties，all-in-one
        // 的配置出口纪律；Spring 途径的业务键不在支持面）；0（随机，测试用）时无法自引用，跳过
        String configuredNodes = options.getConfiguredDiscoveryNodes();
        if ((configuredNodes == null || configuredNodes.isBlank()) && options.getDiscoveryGrpcPort() > 0) {
            args.add("--artemis.discovery.nodes=127.0.0.1:" + options.getDiscoveryGrpcPort());
        }
        return args;
    }

    /** 上下文主 gRPC 端口（registry 排除 peer lifecycle，其余单 lifecycle 直取）。 */
    static int grpcPort(ConfigurableApplicationContext context) {
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

    /** 三上下文句柄：暴露实际端口；close 幂等并按 discovery → management → registry 顺序关闭。 */
    public static final class Node implements AutoCloseable {

        private final ConfigurableApplicationContext registry;

        private final ConfigurableApplicationContext management;

        private final ConfigurableApplicationContext discovery;

        private final AtomicBoolean closed = new AtomicBoolean();

        private final Thread shutdownHook;

        Node(
                ConfigurableApplicationContext registry,
                ConfigurableApplicationContext management,
                ConfigurableApplicationContext discovery) {
            this.registry = registry;
            this.management = management;
            this.discovery = discovery;
            this.shutdownHook = new Thread(this::close, "artemis-all-in-one-shutdown");
        }

        Thread shutdownHook() {
            return shutdownHook;
        }

        public ConfigurableApplicationContext registry() {
            return registry;
        }

        public ConfigurableApplicationContext management() {
            return management;
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

        public int managementGrpcPort() {
            return grpcPort(management);
        }

        public int managementHttpPort() {
            return httpPort(management);
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
            // 依赖反序：discovery 先关（停上游/规则流订阅），management 次之（停规则源与 registry
            // 查询），registry 最后（排水 + 终刷快照）；先关失败不阻断后关，异常带回
            RuntimeException failure = null;
            for (ConfigurableApplicationContext context : List.of(discovery, management, registry)) {
                try {
                    context.close();
                } catch (RuntimeException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private static int httpPort(ConfigurableApplicationContext context) {
            return ((WebServerApplicationContext) context).getWebServer().getPort();
        }
    }
}
