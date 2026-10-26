package org.mydotey.ai.artemis.discovery.server.http;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.mydotey.ai.artemis.discovery.application.DiscoveryAdminService;
import org.mydotey.ai.artemis.discovery.domain.ManagementState;
import org.mydotey.ai.artemis.discovery.domain.ServiceDetail;
import org.mydotey.ai.artemis.discovery.domain.ServiceKey;
import org.mydotey.ai.artemis.discovery.domain.ServiceView;
import org.mydotey.ai.artemis.discovery.domain.SubscriptionInfo;
import org.mydotey.ai.artemis.discovery.domain.UpstreamStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接入层：发现管理面只读 Admin API（D-A1–A6）。契约见 docs/api/discovery-admin-api.md；
 * 认证由 /admin/* HTTP 过滤器承担，方法一行委派应用服务。
 */
@RestController
@RequestMapping("/admin/v1")
public final class AdminController {

    private final DiscoveryAdminService adminService;

    public AdminController(DiscoveryAdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/subscriptions")
    public SubscriptionsResponse subscriptions() {
        return new SubscriptionsResponse(
                adminService.subscriptions().stream().map(AdminController::view).toList());
    }

    @GetMapping("/services")
    public ServicesResponse services(
            @RequestParam(name = "namespace", required = false) String namespace,
            @RequestParam(name = "cluster", required = false) String cluster) {
        return new ServicesResponse(adminService.services(namespace, cluster));
    }

    @GetMapping("/services/{namespace}/{service}")
    public ServiceDetail service(@PathVariable("namespace") String namespace, @PathVariable("service") String service) {
        return adminService.service(new ServiceKey(namespace, service));
    }

    @GetMapping("/upstreams")
    public UpstreamsResponse upstreams() {
        var view = adminService.upstreams();
        return new UpstreamsResponse(
                view.upstreams(),
                new ManagementView(view.managementEnabled(), view.managementState(), view.managementAppliedVersion()));
    }

    /** 契约输出形态（D-A3）：选择器拍平为 services/labels/clusters，集合稳定排序。 */
    private static SubscriptionView view(SubscriptionInfo info) {
        List<ServiceKey> services = new ArrayList<>(info.selector().services());
        services.sort(Comparator.comparing(ServiceKey::namespace).thenComparing(ServiceKey::service));
        List<String> clusters = new ArrayList<>(info.selector().clusters());
        clusters.sort(Comparator.naturalOrder());
        return new SubscriptionView(
                info.id(),
                info.subscriber(),
                info.establishedAt(),
                services,
                info.selector().labels(),
                clusters);
    }

    /** HTTP 响应包装（与 management Admin API 同风格）。 */
    public record SubscriptionsResponse(List<SubscriptionView> subscriptions) {}

    public record SubscriptionView(
            long id,
            String subscriber,
            Instant establishedAt,
            List<ServiceKey> services,
            Map<String, String> labels,
            List<String> clusters) {}

    public record ServicesResponse(List<ServiceView> services) {}

    public record UpstreamsResponse(List<UpstreamStatus> upstreams, ManagementView management) {}

    /** appliedVersion：本节点已应用规则集版本（-1 = 尚未应用；phase-5 排障对照）。 */
    public record ManagementView(boolean enabled, ManagementState state, long appliedVersion) {}
}
