package org.mydotey.ai.artemis.management.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.common.governance.EjectionScope;
import org.mydotey.ai.artemis.management.domain.EjectionAction;
import org.mydotey.ai.artemis.management.domain.EjectionRecord;
import org.mydotey.ai.artemis.management.domain.EjectionTarget;
import org.mydotey.ai.artemis.management.domain.ServiceKey;

class InMemoryEjectionStoreTest {

    private static final ServiceKey SERVICE = new ServiceKey("ns", "svc");
    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");

    private final InMemoryEjectionStore store = new InMemoryEjectionStore();

    @Test
    void should_append_and_return_with_sequence() {
        EjectionRecord stored = store.append(eject(EjectionTarget.instance(SERVICE, "i1"), "r1"));

        assertThat(stored.sequence()).isEqualTo(1);
        assertThat(store.append(eject(EjectionTarget.instance(SERVICE, "i2"), "r2"))
                        .sequence())
                .isEqualTo(2);
    }

    @Test
    void should_find_all_in_append_order_across_scopes() {
        store.append(eject(EjectionTarget.instance(SERVICE, "i1"), "r1"));
        store.append(eject(EjectionTarget.selector(EjectionScope.ZONE, "dc-2"), "r2"));
        store.append(new EjectionRecord(
                EjectionTarget.instance(SERVICE, "i1"), EjectionAction.RECOVER, null, "admin", NOW.plusSeconds(60), 0));

        assertThat(store.findAll())
                .extracting(
                        record -> record.action(),
                        record -> record.target().instanceId(),
                        record -> record.target().value())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(EjectionAction.EJECT, "i1", null),
                        org.assertj.core.groups.Tuple.tuple(EjectionAction.EJECT, null, "dc-2"),
                        org.assertj.core.groups.Tuple.tuple(EjectionAction.RECOVER, "i1", null));
        assertThat(store.findAll().get(2).sequence()).isEqualTo(3);
    }

    private static EjectionRecord eject(EjectionTarget target, String reason) {
        return new EjectionRecord(target, EjectionAction.EJECT, reason, "admin", NOW, 0);
    }
}
