package org.mydotey.ai.artemis.discovery.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProjectionDiffTest {

    private static final ServiceKey ORDER = new ServiceKey("default", "order-service");
    private static final ServiceKey PAY = new ServiceKey("default", "pay-service");

    @Test
    void should_diff_vanished_new_and_changed_services() {
        ServiceInstances oldOrder = service(ORDER, instance("i1", 8080), instance("i2", 8080));
        ServiceInstances freshOrder = service(ORDER, instance("i2", 9090), instance("i3", 8080)); // i2 变更、i3 新增、i1 删除
        ServiceInstances oldPay = service(PAY, instance("p1", 8080)); // 覆盖后消失

        List<ServiceChange> diff = ProjectionDiff.diff(byKey(oldOrder, oldPay), List.of(freshOrder));

        assertThat(diff).hasSize(2);
        ServiceChange order = findByKey(diff, ORDER);
        // i2 UPDATE + i3 NEW 按新序在前，i1 DELETE 按旧序在后（确定序）
        assertThat(order.changes())
                .extracting(c -> c.instance().instanceId(), InstanceChange::type)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("i2", ChangeType.UPDATE),
                        org.assertj.core.groups.Tuple.tuple("i3", ChangeType.NEW),
                        org.assertj.core.groups.Tuple.tuple("i1", ChangeType.DELETE));
        ServiceChange pay = findByKey(diff, PAY);
        assertThat(pay.changes()).hasSize(1);
        assertThat(pay.changes().get(0).type()).isEqualTo(ChangeType.DELETE);
        assertThat(pay.changes().get(0).instance().instanceId()).isEqualTo("p1");
    }

    @Test
    void should_diff_to_empty_when_registry_cold_restarts() {
        // registry 全冷重启（新快照空表）：diff = 全 DELETE（消费方正确感知服务消失）
        List<ServiceChange> diff = ProjectionDiff.diff(byKey(service(ORDER, instance("i1", 8080))), List.of());

        assertThat(diff).hasSize(1);
        assertThat(diff.get(0).key()).isEqualTo(ORDER);
        assertThat(diff.get(0).changes()).hasSize(1);
        assertThat(diff.get(0).changes().get(0).type()).isEqualTo(ChangeType.DELETE);
    }

    @Test
    void should_diff_nothing_when_identical() {
        ServiceInstances snapshot = service(ORDER, instance("i1", 8080));
        assertThat(ProjectionDiff.diff(byKey(snapshot), List.of(snapshot))).isEmpty();
    }

    private static ServiceChange findByKey(List<ServiceChange> changes, ServiceKey key) {
        return changes.stream()
                .filter(change -> change.key().equals(key))
                .findFirst()
                .orElseThrow();
    }

    private static Map<ServiceKey, ServiceInstances> byKey(ServiceInstances... services) {
        Map<ServiceKey, ServiceInstances> byKey = new HashMap<>();
        for (ServiceInstances service : services) {
            byKey.put(service.key(), service);
        }
        return byKey;
    }

    private static ServiceInstances service(ServiceKey key, Instance... instances) {
        return new ServiceInstances(key, List.of(instances));
    }

    private static Instance instance(String instanceId, int port) {
        return new Instance(instanceId, "10.0.0.1", port, InstanceStatus.UP, 100, Map.of());
    }
}
