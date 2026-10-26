package org.mydotey.ai.artemis.management.server.http;

import java.util.List;
import org.mydotey.ai.artemis.management.application.ManagementService;
import org.mydotey.ai.artemis.management.application.PersistenceInfo;
import org.mydotey.ai.artemis.management.domain.ConfigProperty;
import org.mydotey.ai.artemis.management.domain.LeaseStatus;
import org.mydotey.ai.artemis.management.domain.RuleStatus;
import org.mydotey.ai.artemis.management.domain.SelfProtectionStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接入层：registry 只读状态排障面（leases/config）+ 持久化运行形态，数据透传 registry 查询结果。
 */
@RestController
@RequestMapping("/admin/v1/status")
public final class StatusController {

    private final ManagementService managementService;
    private final PersistenceInfo persistenceInfo;

    public StatusController(ManagementService service, PersistenceInfo persistenceInfo) {
        this.managementService = service;
        this.persistenceInfo = persistenceInfo;
    }

    @GetMapping("/leases")
    public LeaseStatus leases(
            @RequestParam(name = "namespace", required = false) String namespace,
            @RequestParam(name = "service", required = false) String service) {
        return managementService.leaseStatus(namespace, service);
    }

    @GetMapping("/protection")
    public SelfProtectionStatus protection(
            @RequestParam(name = "namespace", required = false) String namespace,
            @RequestParam(name = "service", required = false) String service,
            @RequestParam(name = "includeNormal", defaultValue = "false") boolean includeNormal) {
        return managementService.selfProtectionStatus(namespace, service, includeNormal);
    }

    @GetMapping("/config")
    public ConfigStatusResponse config() {
        return new ConfigStatusResponse(managementService.configStatus());
    }

    @GetMapping("/persistence")
    public PersistenceInfo persistence() {
        return persistenceInfo;
    }

    /** 规则生效水位（phase-5）：当前版本 + 各活订阅方已应用版本 + 已生效节点数。 */
    @GetMapping("/rules")
    public RuleStatus rules() {
        return managementService.ruleStatus();
    }

    public record ConfigStatusResponse(List<ConfigProperty> properties) {}
}
