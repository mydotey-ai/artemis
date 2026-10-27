-- v1.0 phase-2：RBAC 角色列（预置角色 ADMIN/OPERATOR/VIEWER，权限矩阵为代码常量）
-- 存量行（v0.4 单管理员）升级即 ADMIN，行为无回退；seed 账户同 ADMIN
ALTER TABLE artemis_account ADD COLUMN role VARCHAR(32) NOT NULL DEFAULT 'ADMIN';
