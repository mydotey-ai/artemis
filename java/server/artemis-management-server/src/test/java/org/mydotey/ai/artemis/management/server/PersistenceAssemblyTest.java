package org.mydotey.ai.artemis.management.server;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.management.application.PersistenceInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** 未配置 DB 时零 DB 装配：无 DataSource bean，PersistenceInfo 为内存态。 */
@SpringBootTest
class PersistenceAssemblyTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private PersistenceInfo persistenceInfo;

    @Test
    void should_assemble_memory_persistence_when_db_not_configured() {
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(persistenceInfo.mode()).isEqualTo(PersistenceInfo.Mode.MEMORY);
        assertThat(persistenceInfo.databaseProduct()).isNull();
        assertThat(persistenceInfo.schemaVersion()).isNull();
    }
}
