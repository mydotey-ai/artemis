-- v0.4 phase-2：单管理员账户表（ADR-021，DB 态管理访问面认证唯一源）
-- password_hash 为自描述 PBKDF2 串（算法/参数内嵌，见 PasswordHasher），格式演进不改表结构
-- datetime(6)：Java 侧 UTC 写入，规避 timestamp 2038/时区语义（双方言各自表达）
CREATE TABLE artemis_account (
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(512) NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    CONSTRAINT pk_artemis_account PRIMARY KEY (username)
);
