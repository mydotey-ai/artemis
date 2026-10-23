package org.mydotey.ai.artemis.registry.server.http;

import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusRequest;
import org.mydotey.ai.artemis.registry.application.ReadinessGate;
import org.mydotey.ai.artemis.registry.application.ReplicationProtoFacade;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 节点列表发现端点（SDK 前置寻址消费）：HTTP GET 返回 GetNodeStatusResponse protobuf bytes，
 * 与 gRPC 观测 API（NodeStatusService.GetNodeStatus）同源同构；Basic 认证经外部 HTTP 通道过滤器生效。
 * readiness 门控与 gRPC 观测 API 的豁免不同：未就绪节点不作寻址应答者（503）——SDK 按 url
 * 列表重试其它地址（单地址=域名/LB 退避重试原址），不被引导到未就绪节点。
 */
@RestController
public final class ClusterNodesController {

    private final ReplicationProtoFacade facade;
    private final ReadinessGate readinessGate;

    public ClusterNodesController(ReplicationProtoFacade facade, ReadinessGate readinessGate) {
        this.facade = facade;
        this.readinessGate = readinessGate;
    }

    @GetMapping(value = "/cluster/nodes", produces = MediaType.APPLICATION_PROTOBUF_VALUE)
    public ResponseEntity<byte[]> clusterNodes() {
        if (!readinessGate.isReady()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_PROTOBUF)
                    .build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PROTOBUF)
                .body(facade.getNodeStatus(GetNodeStatusRequest.getDefaultInstance())
                        .toByteArray());
    }
}
