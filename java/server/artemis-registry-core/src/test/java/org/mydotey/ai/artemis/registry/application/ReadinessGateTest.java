package org.mydotey.ai.artemis.registry.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 三态状态机：NOT_READY → READY → DOWN（优雅停机标记），DOWN 终态优先级最高。 */
class ReadinessGateTest {

    @Test
    void should_transitStates_when_markReadyAndMarkDown() {
        ReadinessGate gate = new ReadinessGate();
        assertThat(gate.isReady()).isFalse();
        assertThat(gate.isDown()).isFalse();

        gate.markReady();
        assertThat(gate.isReady()).isTrue();
        assertThat(gate.isDown()).isFalse();

        gate.markDown();
        assertThat(gate.isReady()).isFalse(); // DOWN 优先于 READY，readiness 联动统一拒新流量
        assertThat(gate.isDown()).isTrue();
    }

    @Test
    void should_markDown_when_notReady() {
        ReadinessGate gate = new ReadinessGate();
        gate.markDown();
        assertThat(gate.isReady()).isFalse();
        assertThat(gate.isDown()).isTrue();
    }

    @Test
    void should_beIdempotent_when_markDownRepeated() {
        ReadinessGate gate = new ReadinessGate();
        gate.markReady();
        gate.markDown();
        gate.markDown();
        assertThat(gate.isDown()).isTrue();
        assertThat(gate.isReady()).isFalse();
    }

    @Test
    void should_notRevive_when_markReadyAfterDown() {
        ReadinessGate gate = new ReadinessGate();
        gate.markDown();
        gate.markReady();
        assertThat(gate.isDown()).isTrue(); // 停机节点不复活
        assertThat(gate.isReady()).isFalse();
    }
}
