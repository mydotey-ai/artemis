package org.mydotey.ai.artemis.client.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.client.InstanceId;
import org.mydotey.ai.artemis.client.ServiceId;
import org.mydotey.ai.artemis.client.ServiceInstance;

class LocalInstanceStoreTest {

    private static final ServiceId ORDER =
            ServiceId.newBuilder().namespace("default").service("order-service").build();

    private static final ServiceId USER =
            ServiceId.newBuilder().namespace("default").service("user-service").build();

    private static ServiceInstance instance(ServiceId service, String instanceId) {
        return ServiceInstance.newBuilder()
                .service(service)
                .instanceId(instanceId)
                .host("10.0.0.1")
                .port(8080)
                .build();
    }

    @Test
    void should_groupByServiceAndUpsert_when_put() {
        LocalInstanceStore store = new LocalInstanceStore();
        store.put(instance(ORDER, "i-1"));
        store.put(instance(ORDER, "i-2"));
        store.put(instance(USER, "u-1"));

        Map<ServiceId, List<ServiceInstance>> snapshot = store.snapshot();
        assertThat(snapshot).containsOnlyKeys(ORDER, USER);
        assertThat(snapshot.get(ORDER))
                .extracting(ServiceInstance::getInstanceId)
                .containsExactlyInAnyOrder("i-1", "i-2");
        assertThat(store.instanceIds()).hasSize(3);

        // upsert：同 key 覆盖，不追加
        store.put(instance(ORDER, "i-1"));
        assertThat(store.snapshot().get(ORDER)).hasSize(2);
    }

    @Test
    void should_cleanEmptyGroup_when_lastInstanceRemoved() {
        LocalInstanceStore store = new LocalInstanceStore();
        store.put(instance(ORDER, "i-1"));
        store.remove(ORDER, "i-1");

        assertThat(store.isEmpty()).isTrue();
        assertThat(store.snapshot()).isEmpty();
    }

    @Test
    void should_beNoop_when_removeUnknown() {
        LocalInstanceStore store = new LocalInstanceStore();
        store.put(instance(ORDER, "i-1"));
        store.remove(USER, "i-1");
        store.remove(ORDER, "i-9");

        assertThat(store.snapshot()).containsOnlyKeys(ORDER);
    }

    @Test
    void should_buildInstanceIdList_when_instanceIds() {
        LocalInstanceStore store = new LocalInstanceStore();
        store.put(instance(ORDER, "i-1"));
        store.put(instance(USER, "u-1"));

        assertThat(store.instanceIds())
                .containsExactlyInAnyOrder(
                        InstanceId.newBuilder().service(ORDER).instanceId("i-1").build(),
                        InstanceId.newBuilder().service(USER).instanceId("u-1").build());
    }
}
