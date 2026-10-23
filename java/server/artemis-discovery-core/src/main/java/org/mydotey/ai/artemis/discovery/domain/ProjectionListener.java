package org.mydotey.ai.artemis.discovery.domain;

import java.util.List;

/** 变更流监听端口（gRPC 回调线程调用，实现方须非阻塞：入队移交，不做应用逻辑）。 */
public interface ProjectionListener {

    /** 订阅建立确认（服务端首条空批，phase-1 契约）。 */
    void onEstablished();

    /** 变更批次到达（按 service 聚合，批内保持产生序）。 */
    void onChanges(List<ServiceChange> batch);

    /** 流终止（对端断流/错误/完成）；此后该订阅不再回调。 */
    void onClosed();
}
