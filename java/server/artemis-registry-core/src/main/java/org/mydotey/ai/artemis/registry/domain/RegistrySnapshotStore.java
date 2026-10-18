package org.mydotey.ai.artemis.registry.domain;

import java.util.Optional;

/**
 * 快照存取端口：读返回最近一次成功落盘的快照字节，无快照返回 empty。
 * 读侧 IO 失败由实现内化（记日志后按无快照返回，不中断启动）；写侧失败抛异常，由调用方
 * （调度器/手动触发）处置。字节格式与编解码归 application 的 dto 边界（端口只存取 bytes，D7）。
 */
public interface RegistrySnapshotStore {

    Optional<byte[]> read();

    void write(byte[] snapshot);
}
