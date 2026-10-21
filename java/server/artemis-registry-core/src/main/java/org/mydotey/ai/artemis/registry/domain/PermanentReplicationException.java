package org.mydotey.ai.artemis.registry.domain;

/**
 * 投递的确定性失败（{@link PeerReplicator#send} 语义）：重试同一批永远不会成功——
 * 整批 ACK_FAIL(INVALID_ARGUMENT)（批次本身非法）或 UNIMPLEMENTED/UNAUTHENTICATED
 * （peer 版本/凭证不匹配，如滚动升级窗口）。扇出 worker 捕获后放弃该批（计数 + WARN、
 * 继续后续批次），不做无限退避重试——队头阻塞会让该 peer 的全部后续复制静默停摆。
 */
public final class PermanentReplicationException extends RuntimeException {

    public PermanentReplicationException(String message) {
        super(message);
    }

    public PermanentReplicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
