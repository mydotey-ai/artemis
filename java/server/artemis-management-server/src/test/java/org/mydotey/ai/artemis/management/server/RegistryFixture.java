package org.mydotey.ai.artemis.management.server;

import java.io.IOException;
import java.nio.file.Files;
import org.mydotey.ai.artemis.registry.server.ArtemisRegistryServer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;

/** registry fixture：惰性启动（DynamicPropertySource 求值时），外部/内部端口均随机；同 JVM 多 IT 类共用。 */
final class RegistryFixture {

    private static ConfigurableApplicationContext context;
    private static int externalPort;
    private static int internalPort;

    private RegistryFixture() {}

    static synchronized int externalPort() {
        start();
        return externalPort;
    }

    static synchronized int internalPort() {
        start();
        return internalPort;
    }

    private static void start() {
        if (context != null) {
            return;
        }
        try {
            internalPort = freePort();
            String snapshotPath = Files.createTempDirectory("artemis-registry-it")
                    .resolve("snapshot.bin")
                    .toString();
            // 测试 classpath 上 management-server 的 application.properties 会先于 registry 的
            // 同名资源被加载；用显式参数钉住 registry 相关配置（端口/应用名/readiness 组）
            context = new SpringApplicationBuilder(ArtemisRegistryServer.class)
                    .run(
                            "--spring.application.name=artemis-registry",
                            "--spring.grpc.server.port=0",
                            "--server.port=0",
                            "--management.server.port=0",
                            "--artemis.registry.internal.port=" + internalPort,
                            "--management.endpoint.health.group.readiness.include=readinessState,registryReadiness",
                            "--artemis.registry.shutdown.drainMs=0", // 测试关闭不等待排水
                            "--artemis.registry.snapshot.path=" + snapshotPath);
            // 主 server lifecycle 名随传输实现而定（netty/shaded），按名称排除内部 peer 后取
            externalPort = context.getBeansOfType(GrpcServerLifecycle.class).entrySet().stream()
                    .filter(entry -> !entry.getKey().equals("peerGrpcServerLifecycle"))
                    .findFirst()
                    .orElseThrow()
                    .getValue()
                    .getPort();
            // 内部端口为固定预分配值；这里仅校验实际绑定与配置一致
            int bound = context.getBeansOfType(GrpcServerLifecycle.class)
                    .get("peerGrpcServerLifecycle")
                    .getPort();
            if (bound != internalPort) {
                throw new IllegalStateException("peer port mismatch: configured " + internalPort + ", bound " + bound);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot start registry fixture", e);
        }
    }

    private static int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("cannot allocate free port", e);
        }
    }

    static synchronized void stop() {
        if (context != null) {
            context.close();
            context = null;
            externalPort = 0;
            internalPort = 0;
        }
    }
}
