-- v0.5 phase-2：发布记录表（资源 release，架构 §5.2）——append-only，回滚历史源由
-- 本表快照承载（规则表始终当前态，phase-1 版本化决策）；记录保留（规则删除不级联删）
-- type = RELEASE / ROLLBACK；rollback_to 指向源记录 id（ROLLBACK 时非空）
-- snapshot 为发布/回滚后完整 targets JSON（两列权重，应用侧 gson 序列化，V4/V5 先例）
-- 时间戳 Java 侧 UTC 写入（双方言各自表达，同 V2–V5 约定）
CREATE TABLE artemis_route_rule_release (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    namespace   VARCHAR(255) NOT NULL,
    service     VARCHAR(255) NOT NULL,
    rule_name   VARCHAR(255) NOT NULL,
    type        VARCHAR(16)  NOT NULL,
    snapshot    TEXT         NOT NULL,
    rollback_to BIGINT       NULL,
    operator    VARCHAR(64)  NOT NULL,
    released_at DATETIME(6)  NOT NULL,
    CONSTRAINT pk_artemis_route_rule_release PRIMARY KEY (id),
    KEY idx_artemis_route_rule_release_rule (namespace, service, rule_name, id)
);
