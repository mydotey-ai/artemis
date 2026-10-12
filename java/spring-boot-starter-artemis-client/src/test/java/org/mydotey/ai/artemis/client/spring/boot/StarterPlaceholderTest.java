package org.mydotey.ai.artemis.client.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.proto.common.v1.ServiceKey;

class StarterPlaceholderTest {

    @Test
    void should_run_placeholder_test_when_building_starter() {
        // starter → artemis-client 依赖链就位（proto 生成类经传递可引用）；自动装配 phase-7
        assertThat(ServiceKey.getDefaultInstance()).isNotNull();
    }
}
