package org.mydotey.ai.artemis.management.application;

import java.util.List;

/**
 * 服务列表分页视图（console 只读版）：total 为过滤后总数（与页内容同源同刻），页大小有界。
 */
public record ServicePage(List<ServiceView> services, int total) {

    public ServicePage {
        services = services == null ? List.of() : List.copyOf(services);
    }
}
