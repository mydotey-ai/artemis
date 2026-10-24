package org.mydotey.ai.artemis.discovery.server.http;

import org.mydotey.ai.artemis.discovery.application.DiscoveryReadinessGate;
import org.mydotey.ai.artemis.discovery.server.upstream.NodeDirectoryConfig;
import org.mydotey.ai.artemis.discovery.server.upstream.UpstreamConfig;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.ResponseStatus;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryNode;
import org.mydotey.ai.artemis.proto.discovery.v1.DiscoveryNodesResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SDK 前置寻址端点（ADR-020）：HTTP GET 返回静态节点目录（{@code DiscoveryNodesResponse}
 * protobuf bytes）。与 registry 同路径 {@code /cluster/nodes}——客户端寻址管线同构；
 * Basic 认证经外部 HTTP 通道过滤器生效（装配处注册到本路径）。readiness 门控：未就绪
 * 节点不作寻址应答者（503），与 {@code /admin/v1} 的免门控排障面区分。
 */
@RestController
public final class ClusterNodesController {

    private final NodeDirectoryConfig nodeDirectory;
    private final DiscoveryReadinessGate readinessGate;

    public ClusterNodesController(NodeDirectoryConfig nodeDirectory, DiscoveryReadinessGate readinessGate) {
        this.nodeDirectory = nodeDirectory;
        this.readinessGate = readinessGate;
    }

    @GetMapping(value = "/cluster/nodes", produces = MediaType.APPLICATION_PROTOBUF_VALUE)
    public ResponseEntity<byte[]> clusterNodes() {
        if (!readinessGate.isReady()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_PROTOBUF)
                    .build();
        }
        DiscoveryNodesResponse.Builder builder = DiscoveryNodesResponse.newBuilder()
                .setStatus(ResponseStatus.newBuilder().setAck(Ack.ACK_SUCCESS).build());
        for (UpstreamConfig.Endpoint endpoint : nodeDirectory.nodes()) {
            builder.addNodes(DiscoveryNode.newBuilder()
                    .setHost(endpoint.host())
                    .setPort(endpoint.port())
                    .build());
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PROTOBUF)
                .body(builder.build().toByteArray());
    }
}
