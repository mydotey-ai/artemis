package org.mydotey.ai.artemis.management.domain;

import java.util.List;

/**
 * 审计端口：append 尽力而为（失败不阻塞治理操作，摘除状态源唯一在记录表）；
 * 查询为排障/合规展示面。
 */
public interface AuditLogStore {

    /** 追加并分配 sequence，返回落库后的完整行。 */
    AuditLog append(AuditLog log);

    /** 时间倒序（最新在前）；operation 可选过滤；limit 上界由接入层约束。 */
    List<AuditLog> find(String operation, int limit);
}
