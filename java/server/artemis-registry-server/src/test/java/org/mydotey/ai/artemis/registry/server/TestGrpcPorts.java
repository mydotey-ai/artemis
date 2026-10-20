package org.mydotey.ai.artemis.registry.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;

/**
 * 双监听测试端口解析：主 server 的 lifecycle bean 名随传输实现（netty/shaded/netty）
 * 而定，不能硬编码；内部 peer listener 固定命名 {@code peerGrpcServerLifecycle}。
 */
final class TestGrpcPorts {

    private static final String PEER_LIFECYCLE = "peerGrpcServerLifecycle";

    private TestGrpcPorts() {}

    static int external(Map<String, GrpcServerLifecycle> lifecycles) {
        return lifecycles.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(PEER_LIFECYCLE))
                .map(entry -> entry.getValue().getPort())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("external gRPC server lifecycle not found"));
    }

    static int internal(Map<String, GrpcServerLifecycle> lifecycles) {
        GrpcServerLifecycle lifecycle = lifecycles.get(PEER_LIFECYCLE);
        if (lifecycle == null) {
            throw new IllegalStateException("internal peer gRPC server lifecycle not found");
        }
        return lifecycle.getPort();
    }

    /**
     * 测试用固定端口：产品配置不接受 0 动态端口，测试必须在启动前预分配。
     * 一次性预留后立即释放（缩小 TOCTOU 窗口）。
     */
    static int freePort() {
        return reservePorts(1).get(0);
    }

    static List<Integer> reservePorts(int count) {
        List<ServerSocket> sockets = new ArrayList<>();
        try {
            List<Integer> ports = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                ServerSocket socket = new ServerSocket(0);
                sockets.add(socket);
                ports.add(socket.getLocalPort());
            }
            return ports;
        } catch (IOException e) {
            throw new IllegalStateException("cannot allocate free ports", e);
        } finally {
            for (ServerSocket socket : sockets) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }
}
