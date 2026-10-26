-- v0.5 phase-1：路由规则表（分组路由/canary/两段发布的规则载体，架构 §5.2 资源 route-rule）
-- 分组以实例 metadata 表达（ADR-013），selector 等值匹配；weight 生效列 + unreleased_weight
-- 编辑列（两段式，release 动作 phase-2）；表只存当前态，历史版本由 release 记录承载（设计 §3）
-- targets 以 JSON 数组存储（TEXT，应用侧 gson 序列化，双方言零方言层差异，V4 metadata 先例）
-- 时间戳 Java 侧 UTC 写入（双方言各自表达，同 V2–V4 约定）
CREATE TABLE artemis_route_rule (
    id          BIGSERIAL    NOT NULL,
    namespace   VARCHAR(255) NOT NULL,
    service     VARCHAR(255) NOT NULL,
    name        VARCHAR(255) NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    targets     TEXT         NOT NULL,
    operator    VARCHAR(64)  NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_artemis_route_rule PRIMARY KEY (id),
    CONSTRAINT uk_artemis_route_rule UNIQUE (namespace, service, name)
);
