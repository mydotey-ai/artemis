package org.mydotey.ai.artemis.client.internal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.client.FakeRegistryServer;
import org.mydotey.ai.artemis.client.TestAwait;
import org.mydotey.scf.facade.StringProperties;

/** TTL 轮换长跑：双 fake 节点 12s 高频轮换（~30 次），心跳持续增长不中断（轮换引擎无累积性死链）。 */
class RotationStressTest {

    private FakeRegistryServer server1;
    private FakeRegistryServer server2;
    private FakeRegistryServer server3;
    private HeartbeatChannel channel;
    private GrpcRegistryClient grpc;
    private FailoverAddressProvider addressProvider;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("artemis.client.heartbeat.intervalMs", "500");
        System.setProperty("artemis.client.reconnect.baseDelayMs", "100");
        System.setProperty("artemis.client.reconnect.maxDelayMs", "2000");
        System.setProperty("artemis.client.registry.connection.ttlMs", "300");
        server1 = new FakeRegistryServer();
        server1.start();
        server2 = new FakeRegistryServer();
        server2.start();
        server3 = new FakeRegistryServer();
        server3.start();
        addressProvider = new FailoverAddressProvider(
                systemProperties(), io.micrometer.core.instrument.Metrics.globalRegistry, "stress");
        java.util.List<NodeEndpoint> endpoints = new java.util.ArrayList<>();
        endpoints.add(new NodeEndpoint(server1.address(), NodeEndpoint.PRIORITY_UP));
        endpoints.add(new NodeEndpoint(server2.address(), NodeEndpoint.PRIORITY_UP));
        endpoints.add(new NodeEndpoint(server3.address(), NodeEndpoint.PRIORITY_UP));
        addressProvider.updateNodes(endpoints);
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.close();
        }
        if (grpc != null) {
            grpc.close(); // channel 只停调度器，ManagedChannel 由 grpc 关闭（防 transport 线程泄漏）
        }
        server1.stop();
        server2.stop();
        server3.stop();
        System.clearProperty("artemis.client.heartbeat.intervalMs");
        System.clearProperty("artemis.client.reconnect.baseDelayMs");
        System.clearProperty("artemis.client.reconnect.maxDelayMs");
        System.clearProperty("artemis.client.registry.connection.ttlMs");
    }

    @Test
    void should_keepHeartbeatsFlowing_when_rotatingRepeatedly() throws Exception {
        GrpcRegistryClient grpc = new GrpcRegistryClient(addressProvider, systemProperties());
        this.grpc = grpc;
        channel = new HeartbeatChannel(
                grpc,
                new HeartbeatChannel.Supplier() {
                    @Override
                    public java.util.Map<
                                    org.mydotey.ai.artemis.client.ServiceId,
                                    java.util.List<org.mydotey.ai.artemis.client.ServiceInstance>>
                            get() {
                        return java.util.Collections.emptyMap();
                    }
                },
                systemProperties(),
                io.micrometer.core.instrument.Metrics.globalRegistry,
                "stress");
        channel.start();

        // 轮换必须覆盖全部 3 节点（对账语义），且心跳持续增长
        TestAwait.await(
                "all three servers received heartbeats via rotation",
                30_000,
                () -> !server1.heartbeatRequests.isEmpty()
                        && !server2.heartbeatRequests.isEmpty()
                        && !server3.heartbeatRequests.isEmpty());
        int lastTotal = 0;
        for (int i = 0; i < 6; i++) {
            Thread.sleep(2000);
            int total = server1.heartbeatRequests.size()
                    + server2.heartbeatRequests.size()
                    + server3.heartbeatRequests.size();
            org.assertj.core.api.Assertions.assertThat(total).isGreaterThan(lastTotal);
            lastTotal = total;
        }
    }

    private static StringProperties systemProperties() {
        java.util.Map<Integer, org.mydotey.scf.ConfigurationSource> sources = new java.util.LinkedHashMap<>();
        sources.put(3, org.mydotey.scf.facade.SimpleConfigurationSources.newSystemPropertiesSource());
        return new StringProperties(
                org.mydotey.scf.facade.ConfigurationManagers.newManager("stress-" + System.nanoTime(), sources));
    }
}
