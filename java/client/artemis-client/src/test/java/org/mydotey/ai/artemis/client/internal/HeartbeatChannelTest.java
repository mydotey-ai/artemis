package org.mydotey.ai.artemis.client.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.client.FakeRegistryServer;
import org.mydotey.ai.artemis.client.TestAwait;
import org.mydotey.ai.artemis.proto.registry.v1.HeartbeatRequest;
import org.mydotey.scf.facade.StringProperties;

/** 心跳引擎内核：seq/ack、立即首跳、断流重连、多址换址、TTL 轮换、ack 丢失不阻塞（直接构造 internal 组件）。 */
class HeartbeatChannelTest {

    private FakeRegistryServer server;
    private HeartbeatChannel channel;
    private FailoverAddressProvider addressProvider;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("artemis.client.heartbeat.intervalMs", "500");
        System.setProperty("artemis.client.reconnect.baseDelayMs", "100");
        System.setProperty("artemis.client.reconnect.maxDelayMs", "2000");
        server = new FakeRegistryServer();
        server.start();
        addressProvider = new FailoverAddressProvider(
                systemProperties(), io.micrometer.core.instrument.Metrics.globalRegistry, "test-client");
        updateNodes(server.address());
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.close();
        }
        server.stop();
        System.clearProperty("artemis.client.heartbeat.intervalMs");
        System.clearProperty("artemis.client.reconnect.baseDelayMs");
        System.clearProperty("artemis.client.reconnect.maxDelayMs");
    }

    private void updateNodes(String... addresses) {
        java.util.List<NodeEndpoint> endpoints = new java.util.ArrayList<>();
        for (String address : addresses) {
            endpoints.add(new NodeEndpoint(address, NodeEndpoint.PRIORITY_UP));
        }
        addressProvider.updateNodes(endpoints);
    }

    private HeartbeatChannel startChannel() {
        GrpcRegistryClient grpc =
                new GrpcRegistryClient(addressProvider, systemProperties(), ClientTls.from(systemProperties()));
        channel = new HeartbeatChannel(
                grpc,
                new HeartbeatChannel.Supplier() {
                    @Override
                    public java.util.Map<
                                    org.mydotey.ai.artemis.client.ServiceId,
                                    java.util.List<org.mydotey.ai.artemis.client.ServiceInstance>>
                            get() {
                        return Collections.emptyMap();
                    }
                },
                systemProperties(),
                io.micrometer.core.instrument.Metrics.globalRegistry,
                "test-client");
        channel.start();
        return channel;
    }

    private static StringProperties systemProperties() {
        java.util.Map<Integer, org.mydotey.scf.ConfigurationSource> sources =
                new java.util.LinkedHashMap<Integer, org.mydotey.scf.ConfigurationSource>();
        sources.put(3, org.mydotey.scf.facade.SimpleConfigurationSources.newSystemPropertiesSource());
        return new StringProperties(org.mydotey.scf.facade.ConfigurationManagers.newManager("test-client", sources));
    }

    @Test
    void should_heartbeatImmediately_when_started() {
        HeartbeatChannel channel = startChannel();

        // 首跳不等首个周期：400ms 内到达（< 500ms 周期首 tick），与机器速度解耦的有界轮询
        TestAwait.await(
                "first heartbeat before first period",
                400,
                () -> !server.heartbeatRequests.isEmpty() && channel.lastAckSeq() >= 1);
    }

    @Test
    void should_sendMonotonicSeqAndTrackAck_when_heartbeat() {
        HeartbeatChannel channel = startChannel();

        TestAwait.await("multiple heartbeats with monotonic seq", 4000, () -> server.heartbeatRequests.size() >= 3);
        long previous = 0;
        for (HeartbeatRequest request : server.heartbeatRequests) {
            assertThat(request.getSeq()).isGreaterThan(previous);
            previous = request.getSeq();
        }
        // ack echo seq 被记录（最终一致跟踪）
        final long lastSeq = previous;
        TestAwait.await("ack tracked", 2000, () -> channel.lastAckSeq() >= lastSeq);
    }

    @Test
    void should_reconnectWithBackoff_when_streamBroken() {
        HeartbeatChannel channel = startChannel();
        TestAwait.await("stream established", 3000, () -> !server.heartbeatRequests.isEmpty());
        int countAtBreak = server.heartbeatRequests.size();

        server.breakHeartbeatStream();

        // 断流后经退避重连（base 100ms）恢复心跳，且新流上 ack 恢复跟踪
        TestAwait.await(
                "heartbeat resumes with ack after reconnect",
                5000,
                () -> server.heartbeatRequests.size() > countAtBreak + 1 && channel.lastAckSeq() > 0);
        assertThat(channel.lastAckSeq()).isGreaterThan(0);
    }

    @Test
    void should_selectRandomlyAndSwitch_when_reconnect() throws Exception {
        FakeRegistryServer backup = new FakeRegistryServer();
        backup.start();
        try {
            updateNodes(server.address(), backup.address());
            startChannel();
            TestAwait.await(
                    "heartbeat arrives at one of the servers",
                    3000,
                    () -> !server.heartbeatRequests.isEmpty() || !backup.heartbeatRequests.isEmpty());
            int totalAtBreak = server.heartbeatRequests.size() + backup.heartbeatRequests.size();

            // 断掉两条可能的活动流（未承流的 fake 断流是 no-op）；重连路径 resetChannel 重新选址
            server.breakHeartbeatStream();
            backup.breakHeartbeatStream();

            TestAwait.await(
                    "heartbeat resumes on re-selected address",
                    5000,
                    () -> server.heartbeatRequests.size() + backup.heartbeatRequests.size() > totalAtBreak + 1);
        } finally {
            backup.stop();
        }
    }

    @Test
    void should_rotateConnection_when_ttlReached() throws Exception {
        FakeRegistryServer backup = new FakeRegistryServer();
        backup.start();
        System.setProperty("artemis.client.registry.connection.ttlMs", "300");
        try {
            updateNodes(server.address(), backup.address());
            startChannel();
            // TTL 到期主动轮换（无退避）：心跳流在两节点间轮转，两端均收到心跳
            TestAwait.await(
                    "heartbeats arrive at both servers via ttl rotation",
                    5000,
                    () -> !server.heartbeatRequests.isEmpty() && !backup.heartbeatRequests.isEmpty());
        } finally {
            System.clearProperty("artemis.client.registry.connection.ttlMs");
            backup.stop();
        }
    }

    @Test
    void should_keepSending_when_ackLost() {
        server.failHeartbeatEcho = true; // 收而不回：模拟 ack 丢失
        HeartbeatChannel channel = startChannel();

        // 不阻塞等待特定 seq 的 ack：心跳持续发送
        TestAwait.await("heartbeats keep flowing without ack", 4000, () -> server.heartbeatRequests.size() >= 3);
        assertThat(channel.lastAckSeq()).isEqualTo(-1L); // 无 ack 也不停发
    }
}
