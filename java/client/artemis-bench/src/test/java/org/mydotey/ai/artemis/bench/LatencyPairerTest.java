package org.mydotey.ai.artemis.bench;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** churn 注入 × 订阅 listener 事件按 service 顺序配对出推送延迟。 */
class LatencyPairerTest {

    @Test
    void should_pair_push_latency_when_events_matched() {
        List<LatencyPairer.Injection> injections = Arrays.asList(
                new LatencyPairer.Injection("svc-a", 1, 1_000),
                new LatencyPairer.Injection("svc-a", 2, 2_000),
                new LatencyPairer.Injection("svc-b", 3, 1_500));
        List<LatencyPairer.Observation> observations = new ArrayList<>();
        observations.add(new LatencyPairer.Observation("svc-a", "sub-0", 1_100));
        observations.add(new LatencyPairer.Observation("svc-b", "sub-0", 1_800));
        observations.add(new LatencyPairer.Observation("svc-a", "sub-0", 2_350));

        List<LatencyPairer.Sample> samples = LatencyPairer.pair(injections, observations);
        assertThat(samples).hasSize(3);
        assertThat(samples.get(0).seq).isEqualTo(1);
        assertThat(samples.get(0).latencyMs).isEqualTo(100);
        assertThat(samples.get(1).service).isEqualTo("svc-b");
        assertThat(samples.get(1).latencyMs).isEqualTo(300);
        assertThat(samples.get(2).seq).isEqualTo(2);
        assertThat(samples.get(2).latencyMs).isEqualTo(350);

        LatencyPairer.Summary summary = LatencyPairer.summarize(samples);
        assertThat(summary.count).isEqualTo(3);
        assertThat(summary.max).isEqualTo(350);
    }

    @Test
    void should_skip_stale_injections_when_observation_precedes() {
        // t1 早于全部未消费 t0（bootstrap/噪声观察）→ 不产生负延迟样本、不作废后续注入；
        // 之后 t1 匹配最早未消费注入（推送合并语义：一次观察只消费一个注入）
        List<LatencyPairer.Injection> injections = Arrays.asList(
                new LatencyPairer.Injection("svc-a", 1, 2_000), new LatencyPairer.Injection("svc-a", 2, 3_000));
        List<LatencyPairer.Observation> observations = new ArrayList<>();
        observations.add(new LatencyPairer.Observation("svc-a", "sub-0", 1_500));
        observations.add(new LatencyPairer.Observation("svc-a", "sub-0", 3_200));

        List<LatencyPairer.Sample> samples = LatencyPairer.pair(injections, observations);
        assertThat(samples).hasSize(1);
        assertThat(samples.get(0).seq).isEqualTo(1);
        assertThat(samples.get(0).latencyMs).isEqualTo(1200);
    }

    @Test
    void should_pair_per_subscriber_when_multiple_subscribers_observe() {
        // 每订阅者独立配对：同一注入对 Q 个订阅者各出一条样本（分布为 per-subscriber 而非 min-of-Q）
        List<LatencyPairer.Injection> injections = Arrays.asList(new LatencyPairer.Injection("svc-a", 1, 1_000));
        List<LatencyPairer.Observation> observations = Arrays.asList(
                new LatencyPairer.Observation("svc-a", "sub-0", 1_100),
                new LatencyPairer.Observation("svc-a", "sub-1", 1_900));

        List<LatencyPairer.Sample> samples = LatencyPairer.pair(injections, observations);
        assertThat(samples).hasSize(2);
        assertThat(samples.get(0).subscriber).isEqualTo("sub-0");
        assertThat(samples.get(0).latencyMs).isEqualTo(100);
        assertThat(samples.get(1).subscriber).isEqualTo("sub-1");
        assertThat(samples.get(1).latencyMs).isEqualTo(900);
    }

    @Test
    void should_return_empty_summary_when_no_samples() {
        assertThat(LatencyPairer.summarize(new ArrayList<>()).count).isZero();
    }
}
