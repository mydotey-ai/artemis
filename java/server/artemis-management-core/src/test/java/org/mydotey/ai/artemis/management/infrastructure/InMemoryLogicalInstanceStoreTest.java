package org.mydotey.ai.artemis.management.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.management.domain.Instance;
import org.mydotey.ai.artemis.management.domain.InstanceStatus;
import org.mydotey.ai.artemis.management.domain.LogicalInstance;
import org.mydotey.ai.artemis.management.domain.ServiceKey;

class InMemoryLogicalInstanceStoreTest {

    private static final ServiceKey SERVICE = new ServiceKey("ns", "svc");

    private final InMemoryLogicalInstanceStore store = new InMemoryLogicalInstanceStore();

    @Test
    void should_return_previous_on_upsert_and_removed_on_delete_when_store_mutates() {
        LogicalInstance original = logical("i1", "10.0.0.1");
        LogicalInstance replaced = logical("i1", "10.0.0.2");

        assertThat(store.upsert(original)).isNull(); // 新增：previous = null
        assertThat(store.upsert(replaced)).isEqualTo(original); // 覆盖：previous = 原值
        assertThat(store.find(SERVICE, "i1")).isEqualTo(replaced);
        assertThat(store.findAll()).containsExactly(replaced);

        assertThat(store.delete(SERVICE, "i1")).isEqualTo(replaced); // 命中删除
        assertThat(store.delete(SERVICE, "i1")).isNull(); // 缺失幂等
        assertThat(store.findAll()).isEmpty();
    }

    @Test
    void should_key_by_service_and_instance_id() {
        // 同 instanceId 不同 service = 不同逻辑实例（唯一键三元组）
        ServiceKey other = new ServiceKey("ns", "other");
        store.upsert(logical("i1", "10.0.0.1"));
        store.upsert(new LogicalInstance(other, instance("i1", "10.0.0.9")));

        List<LogicalInstance> all = store.findAll();
        assertThat(all).hasSize(2);
        assertThat(store.find(SERVICE, "i1")).isNotEqualTo(store.find(other, "i1"));
    }

    private static LogicalInstance logical(String instanceId, String host) {
        return new LogicalInstance(SERVICE, instance(instanceId, host));
    }

    private static Instance instance(String instanceId, String host) {
        return new Instance(instanceId, host, 8080, InstanceStatus.UP, 100, Map.of());
    }
}
