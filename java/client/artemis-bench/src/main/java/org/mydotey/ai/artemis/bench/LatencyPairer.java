package org.mydotey.ai.artemis.bench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 推送延迟配对（纯函数，单机同钟前提）：churn 注入事件 t0 ×（service, seq）与订阅 listener 事件 t1
 * （service, subscriber）配对——**每订阅者独立配对**（同一注入对 Q 个订阅者各出一条样本，
 * 分布为 per-subscriber 而非 min-of-Q）；每个 t1 消费该 service 最早未消费且 t0 ≤ t1 的注入。
 */
public final class LatencyPairer {

    /** t0 事件：service、序号、注入时刻（epoch ms）。 */
    public static final class Injection {

        public final String service;
        public final long seq;
        public final long timestamp;

        public Injection(String service, long seq, long timestamp) {
            this.service = service;
            this.seq = seq;
            this.timestamp = timestamp;
        }
    }

    /** t1 事件：service、订阅者、观察时刻（epoch ms）。 */
    public static final class Observation {

        public final String service;
        public final String subscriber;
        public final long timestamp;

        public Observation(String service, String subscriber, long timestamp) {
            this.service = service;
            this.subscriber = subscriber;
            this.timestamp = timestamp;
        }
    }

    /** 配对结果：一次变更注入到某订阅者感知的端到端延迟。 */
    public static final class Sample {

        public final String service;
        public final String subscriber;
        public final long seq;
        public final long injectedAt;
        public final long observedAt;
        public final long latencyMs;

        private Sample(String service, String subscriber, long seq, long injectedAt, long observedAt) {
            this.service = service;
            this.subscriber = subscriber;
            this.seq = seq;
            this.injectedAt = injectedAt;
            this.observedAt = observedAt;
            this.latencyMs = observedAt - injectedAt;
        }
    }

    /** 延迟分布摘要（ms）。 */
    public static final class Summary {

        public final int count;
        public final long p50;
        public final long p95;
        public final long p99;
        public final long max;

        private Summary(int count, long p50, long p95, long p99, long max) {
            this.count = count;
            this.p50 = p50;
            this.p95 = p95;
            this.p99 = p99;
            this.max = max;
        }

        @Override
        public String toString() {
            return "count=" + count + " p50=" + p50 + "ms p95=" + p95 + "ms p99=" + p99 + "ms max=" + max + "ms";
        }
    }

    private LatencyPairer() {}

    public static List<Sample> pair(List<Injection> injections, List<Observation> observations) {
        Map<String, List<Injection>> byService = new HashMap<>();
        for (Injection injection : injections) {
            byService.computeIfAbsent(injection.service, k -> new ArrayList<>()).add(injection);
        }
        for (List<Injection> list : byService.values()) {
            Collections.sort(list, (a, b) -> Long.compare(a.timestamp, b.timestamp));
        }
        // 每订阅者独立游标：同注入可被各订阅者各消费一次
        Map<String, Map<String, Integer>> cursorsBySubscriber = new HashMap<>();
        List<Sample> samples = new ArrayList<>();
        for (Observation observation : observations) {
            List<Injection> pending = byService.get(observation.service);
            if (pending == null) {
                continue;
            }
            Map<String, Integer> cursors =
                    cursorsBySubscriber.computeIfAbsent(observation.subscriber, k -> new HashMap<>());
            int cursor = cursors.getOrDefault(observation.service, 0);
            while (cursor < pending.size() && pending.get(cursor).timestamp > observation.timestamp) {
                cursor++;
            }
            if (cursor >= pending.size()) {
                continue;
            }
            Injection matched = pending.get(cursor);
            cursors.put(observation.service, cursor + 1);
            samples.add(new Sample(
                    matched.service, observation.subscriber, matched.seq, matched.timestamp, observation.timestamp));
        }
        return samples;
    }

    public static Summary summarize(List<Sample> samples) {
        if (samples.isEmpty()) {
            return new Summary(0, 0, 0, 0, 0);
        }
        List<Long> latencies = new ArrayList<>();
        for (Sample sample : samples) {
            latencies.add(sample.latencyMs);
        }
        Collections.sort(latencies);
        return new Summary(
                latencies.size(),
                percentile(latencies, 0.50),
                percentile(latencies, 0.95),
                percentile(latencies, 0.99),
                latencies.get(latencies.size() - 1));
    }

    private static long percentile(List<Long> sorted, double quantile) {
        int index = (int) Math.min(sorted.size() - 1, Math.round(quantile * (sorted.size() - 1)));
        return sorted.get(index);
    }
}
