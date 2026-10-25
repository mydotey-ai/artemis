package org.mydotey.ai.artemis.management.application;

/**
 * management 持久化运行形态：未配置 DB 时内存态（期 1 行为），配置 DB 后为 DB 态。
 * 运行形态为静态装配结果（DB 连接是启动期静态配置），运行期不切换。
 *
 * @param databaseProduct DB 态时为 JDBC DatabaseMetaData 产品名（如 MySQL/PostgreSQL），内存态为 null
 * @param schemaVersion DB 态时为最新已应用 migration 版本（Flyway 版本号原样字符串，如 "1"），
 *     migration 被禁用或不可读时为 null；内存态为 null
 * @since 0.4
 */
public record PersistenceInfo(Mode mode, String databaseProduct, String schemaVersion) {

    public enum Mode {
        MEMORY,
        DATABASE
    }

    public static PersistenceInfo memory() {
        return new PersistenceInfo(Mode.MEMORY, null, null);
    }
}
