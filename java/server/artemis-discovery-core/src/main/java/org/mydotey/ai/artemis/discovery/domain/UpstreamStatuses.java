package org.mydotey.ai.artemis.discovery.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上游同步状态表（D-F5）：per-cluster 最近状态——同步引擎状态转移时上报，查询/对账响应
 * 装饰新鲜度时快照。弱一致（CHM），快照顺序不保证（消费方按 cluster 键控）；多集群
 * （phase-5）每同步器独立上报，map 形态天然支持。
 */
public final class UpstreamStatuses {

    private final ConcurrentHashMap<String, UpstreamState> states = new ConcurrentHashMap<>();

    public void update(String cluster, UpstreamState state) {
        states.put(cluster, state);
    }

    public List<UpstreamStatus> snapshot() {
        List<UpstreamStatus> snapshot = new ArrayList<>(states.size());
        for (var entry : states.entrySet()) {
            snapshot.add(new UpstreamStatus(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(snapshot);
    }
}
