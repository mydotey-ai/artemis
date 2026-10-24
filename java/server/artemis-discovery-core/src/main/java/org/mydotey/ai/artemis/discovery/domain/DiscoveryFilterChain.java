package org.mydotey.ai.artemis.discovery.domain;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * 过滤器链（架构 §4.2）：有序执行；单 filter 抛异常视为放行（逐 filter fail-open，D-E3）
 * + WARN——规则源故障不得中断发现服务。
 *
 * <p>全可见时 {@link #filter} 返回原 List 引用——checksum 缓存零计算路径依赖此约定
 * （D-E8，phase-4 D-F3 保留）。
 */
@Slf4j
public final class DiscoveryFilterChain {

    private static final DiscoveryFilterChain EMPTY = new DiscoveryFilterChain(List.of());

    private final List<DiscoveryFilter> filters;

    private DiscoveryFilterChain(List<DiscoveryFilter> filters) {
        this.filters = filters;
    }

    public static DiscoveryFilterChain empty() {
        return EMPTY;
    }

    public static DiscoveryFilterChain of(DiscoveryFilter... filters) {
        return new DiscoveryFilterChain(List.of(filters));
    }

    public boolean isEmpty() {
        return filters.isEmpty();
    }

    public boolean isVisible(ServiceKey service, Instance instance) {
        for (DiscoveryFilter filter : filters) {
            try {
                if (!filter.isVisible(service, instance)) {
                    return false;
                }
            } catch (RuntimeException e) {
                log.warn(
                        "discovery filter failed open, filter={}",
                        filter.getClass().getSimpleName(),
                        e);
            }
        }
        return true;
    }

    /** 过滤后实例集；全可见返回原 List 引用（D-E8）。 */
    public List<Instance> filter(ServiceKey service, List<Instance> instances) {
        if (filters.isEmpty()) {
            return instances;
        }
        List<Instance> result = new ArrayList<>(instances.size());
        boolean changed = false;
        for (Instance instance : instances) {
            if (isVisible(service, instance)) {
                result.add(instance);
            } else {
                changed = true;
            }
        }
        return changed ? List.copyOf(result) : instances;
    }
}
