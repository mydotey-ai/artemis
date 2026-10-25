-- v0.4 phase-4：逻辑实例表（管理面维护的静态实例，唯一键 (namespace, service, instance_id)）
-- metadata 以 JSON 字符串存储（TEXT，应用侧 gson 序列化，双方言零方言层差异）
-- 时间戳 Java 侧 UTC 写入（双方言各自表达，同 V2/V3 约定）
CREATE TABLE artemis_logical_instance (
    id          BIGSERIAL    NOT NULL,
    namespace   VARCHAR(255) NOT NULL,
    service     VARCHAR(255) NOT NULL,
    instance_id VARCHAR(255) NOT NULL,
    host        VARCHAR(255) NOT NULL,
    port        INT          NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    weight      INT          NOT NULL,
    metadata    TEXT         NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_artemis_logical_instance PRIMARY KEY (id),
    CONSTRAINT uk_artemis_logical_instance UNIQUE (namespace, service, instance_id)
);
