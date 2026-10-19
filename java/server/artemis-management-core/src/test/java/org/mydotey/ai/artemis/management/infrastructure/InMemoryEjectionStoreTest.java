package org.mydotey.ai.artemis.management.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.management.domain.EjectionRecord;
import org.mydotey.ai.artemis.management.domain.InstanceKey;
import org.mydotey.ai.artemis.management.domain.ServiceKey;

class InMemoryEjectionStoreTest {

    private static final ServiceKey SERVICE = new ServiceKey("ns", "svc");
    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");

    private final InMemoryEjectionStore store = new InMemoryEjectionStore();

    @Test
    void should_eject_and_recover_store_record_when_operations() {
        InstanceKey instance = new InstanceKey(SERVICE, "i1");

        store.eject(new EjectionRecord(instance, "maintenance", NOW));

        assertThat(store.findByService(SERVICE)).containsExactly(new EjectionRecord(instance, "maintenance", NOW));
        assertThat(store.recover(instance)).isTrue();
        assertThat(store.findByService(SERVICE)).isEmpty();
    }

    @Test
    void should_keep_first_record_when_ejecting_twice() {
        InstanceKey instance = new InstanceKey(SERVICE, "i1");
        EjectionRecord first = new EjectionRecord(instance, "first", NOW);

        store.eject(first);
        EjectionRecord second = store.eject(new EjectionRecord(instance, "second", NOW.plusSeconds(60)));

        assertThat(second).isEqualTo(first);
        assertThat(store.findByService(SERVICE)).containsExactly(first);
    }

    @Test
    void should_return_false_when_recovering_not_ejected() {
        assertThat(store.recover(new InstanceKey(SERVICE, "missing"))).isFalse();
    }

    @Test
    void should_list_records_by_service_when_multiple_instances() {
        store.eject(new EjectionRecord(new InstanceKey(SERVICE, "i2"), "r2", NOW));
        store.eject(new EjectionRecord(new InstanceKey(SERVICE, "i1"), "r1", NOW));
        store.eject(new EjectionRecord(new InstanceKey(new ServiceKey("ns", "other"), "i9"), "r9", NOW));

        List<EjectionRecord> records = store.findByService(SERVICE);

        assertThat(records).extracting(record -> record.instance().instanceId()).containsExactly("i1", "i2");
        assertThat(store.countByService()).containsEntry(SERVICE, 2L).containsEntry(new ServiceKey("ns", "other"), 1L);
    }
}
