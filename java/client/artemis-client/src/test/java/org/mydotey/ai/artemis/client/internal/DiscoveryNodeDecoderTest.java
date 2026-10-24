package org.mydotey.ai.artemis.client.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryNode;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryNodesResponse;

/** 节点目录响应解析：全部 UP 候选，非法端点跳过。 */
class DiscoveryNodeDecoderTest {

    @Test
    void should_decode_discovery_nodes_response_into_address_pool() throws Exception {
        DiscoveryNodesResponse response = DiscoveryNodesResponse.newBuilder()
                .addNodes(DiscoveryNode.newBuilder().setHost("10.0.0.1").setPort(9095))
                .addNodes(DiscoveryNode.newBuilder().setHost("[fd00::1]").setPort(9095))
                .addNodes(DiscoveryNode.newBuilder().setHost("bad").setPort(0)) // 非法端口跳过
                .build();

        List<NodeEndpoint> endpoints = DefaultDiscoveryClient.discoveryDecoder().decode(response.toByteArray());

        assertThat(endpoints).extracting(e -> e.address).containsExactly("10.0.0.1:9095", "[fd00::1]:9095");
        assertThat(endpoints).allMatch(e -> e.priority == NodeEndpoint.PRIORITY_UP);
    }
}
