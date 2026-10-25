package org.mydotey.ai.artemis.management.server.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.management.application.PersistenceInfo;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * DB 可选装配（双轨）：配置 {@code spring.datasource.url} 才建 DataSource（未配置时零 DB
 * 装配、management 内存态运行）；配置后 Boot 的 Flyway/事务/JdbcTemplate 自动配置因
 * DataSource bean 存在而接管，migration 启动期执行。DB 连接是启动期静态配置，运行期不切换。
 *
 * <p>组合 classpath 约束：本类方法签名与泛型参数不得引用 Flyway/Hikari 类型（optional
 * 依赖组在组合 classpath 上可能缺失，签名/泛型引用会使反射 introspection 与依赖解析失败）；
 * Flyway 类型只在方法体内引用（惰性加载，无 DataSource 时不会触达）。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class PersistenceConfiguration {

    @Bean
    @ConditionalOnProperty("spring.datasource.url")
    DataSource dataSource(Environment environment) {
        String url = environment.getRequiredProperty("spring.datasource.url");
        if (!StringUtils.hasText(url)) {
            // 空值配置显式失败，避免退化为静默内存态或晦涩的连接池错误
            throw new IllegalStateException("spring.datasource.url is configured but empty");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(environment.getProperty("spring.datasource.username"));
        config.setPassword(environment.getProperty("spring.datasource.password"));
        // 连接池参数配置面保留：spring.datasource.hikari.*（字段名直绑 HikariConfig）
        Binder.get(environment).bind("spring.datasource.hikari", Bindable.ofInstance(config));
        return new HikariDataSource(config);
    }

    @Bean
    PersistenceInfo persistenceInfo(
            ObjectProvider<DataSource> dataSourceProvider, ApplicationContext applicationContext) {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            return PersistenceInfo.memory();
        }
        // 本 bean 参数为 ObjectProvider，Boot 的数据库初始化依赖检测不识别，须显式触发
        // migration 先行（initializer 构造即执行 migrate），随后查表才可见 schema 版本
        applicationContext.getBeanProvider(FlywayMigrationInitializer.class).getIfAvailable();
        return databasePersistence(dataSource);
    }

    /** DB 态：产品名取自元数据；schema 版本读 Flyway history 末行（installed_rank 序，非字典序）。 */
    private static PersistenceInfo databasePersistence(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            return new PersistenceInfo(PersistenceInfo.Mode.DATABASE, product, schemaVersion(connection));
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read database product name", e);
        }
    }

    /**
     * 版本查询失败（如 Flyway 被禁用 {@code spring.flyway.enabled=false}、自定义 history 表名）
     * 时降级为 null 而非拒绝启动——DB 态本身由 url 配置决定，migration 管理方式属运维自由。
     */
    private static String schemaVersion(Connection connection) {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")) {
            return result.next() ? result.getString(1) : null;
        } catch (SQLException e) {
            log.warn("cannot read schema version from flyway_schema_history, reporting null", e);
            return null;
        }
    }
}
