package org.mydotey.ai.artemis.management.server.http;

import java.util.List;
import org.mydotey.ai.artemis.management.application.ManagementService;
import org.mydotey.ai.artemis.management.domain.ConfigProperty;
import org.mydotey.ai.artemis.management.domain.LeaseStatus;
import org.mydotey.ai.artemis.management.domain.SelfProtectionStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接入层：registry 只读状态排障面（leases/config），数据透传 registry 查询结果。
 */
@RestController
@RequestMapping("/admin/v1/status")
public final class StatusController {

    private final ManagementService managementService;

    public StatusController(ManagementService service) {
        this.managementService = service;
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

    public record ConfigStatusResponse(List<ConfigProperty> properties) {}
}
