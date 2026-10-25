-- v0.4 phase-3：四级摘除记录表（操作记录即状态：EJECT 可叠加、RECOVER 反向对消）
-- + 治理操作审计表（摘除/恢复为摘要行，完整留痕在记录表；其余治理操作全量记录）
-- 时间戳 Java 侧 UTC 写入（双方言各自表达，同 V2 约定）
CREATE TABLE artemis_ejection_record (
    id           BIGSERIAL    NOT NULL,
    scope        VARCHAR(16)  NOT NULL,
    namespace    VARCHAR(255) NULL,
    service      VARCHAR(255) NULL,
    instance_id  VARCHAR(512) NULL,
    target_value VARCHAR(512) NULL,
    action       VARCHAR(8)   NOT NULL,
    reason       VARCHAR(1024) NULL,
    operator     VARCHAR(64)  NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_artemis_ejection_record PRIMARY KEY (id)
);

CREATE INDEX idx_ejection_scope_value ON artemis_ejection_record (scope, target_value);
CREATE INDEX idx_ejection_namespace_service ON artemis_ejection_record (namespace, service);

CREATE TABLE artemis_audit_log (
    id         BIGSERIAL    NOT NULL,
    operation  VARCHAR(64)  NOT NULL,
    target     VARCHAR(1024) NOT NULL,
    operator   VARCHAR(64)  NOT NULL,
    result     VARCHAR(16)  NOT NULL,
    detail     VARCHAR(2048) NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_artemis_audit_log PRIMARY KEY (id)
);

CREATE INDEX idx_audit_operation ON artemis_audit_log (operation, id);
