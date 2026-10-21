package org.mydotey.ai.artemis.registry.domain;

/**
 * 出站端口：向单个 peer 投递增量复制批量（gRPC 传输在 server 装配层实现）。
 *
 * <p>阻塞语义：成功返回，失败抛异常——调用方（扇出 worker）以失败-退避-重发处理，
 * 同一 peer 串行投递、重试保序（契约 {@code Replicate} 注释）。
 */
@FunctionalInterface
public interface PeerReplicator extends AutoCloseable {

    void send(ClusterMember peer, ReplicationBatch batch);

    @Override
    default void close() {}
}
