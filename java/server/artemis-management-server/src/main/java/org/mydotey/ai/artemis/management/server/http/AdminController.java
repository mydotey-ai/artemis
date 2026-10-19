package org.mydotey.ai.artemis.management.server.http;

import java.util.List;
import org.mydotey.ai.artemis.management.application.EjectionView;
import org.mydotey.ai.artemis.management.application.ManagementService;
import org.mydotey.ai.artemis.management.application.ServiceDetail;
import org.mydotey.ai.artemis.management.application.ServiceView;
import org.mydotey.ai.artemis.management.domain.ServiceKey;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接入层：实例管理 Admin API（列表/详情/摘除/恢复）。契约见 docs/api/admin-api.md。
 * 契约级校验（空白参数、reason 长度）在信任边界一次完成。
 */
@RestController
@RequestMapping("/admin/v1/services")
public final class AdminController {

    private static final int MAX_REASON_LENGTH = 1024;

    private final ManagementService managementService;

    public AdminController(ManagementService service) {
        this.managementService = service;
    }

    @GetMapping
    public ListServicesResponse listServices(@RequestParam(name = "namespace", required = false) String namespace) {
        return new ListServicesResponse(managementService.listServices(namespace));
    }

    @GetMapping("/{namespace}/{service}")
    public ServiceDetail getService(
            @PathVariable("namespace") String namespace,
            @PathVariable("service") String service,
            @RequestParam(name = "includeEjected", defaultValue = "false") boolean includeEjected) {
        return managementService.getService(key(namespace, service), includeEjected);
    }

    @PostMapping("/{namespace}/{service}/instances/{instanceId}/eject")
    public EjectionView eject(
            @PathVariable("namespace") String namespace,
            @PathVariable("service") String service,
            @PathVariable("instanceId") String instanceId,
            @RequestBody(required = false) EjectRequest request) {
        ServiceKey key = key(namespace, service);
        requireNonBlank(instanceId, "instanceId");
        String reason = request == null ? null : request.reason();
        if (reason != null && reason.codePointCount(0, reason.length()) > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("reason must be at most " + MAX_REASON_LENGTH + " characters");
        }
        return managementService.eject(key, instanceId, reason);
    }

    @PostMapping("/{namespace}/{service}/instances/{instanceId}/recover")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recover(
            @PathVariable("namespace") String namespace,
            @PathVariable("service") String service,
            @PathVariable("instanceId") String instanceId) {
        ServiceKey key = key(namespace, service);
        requireNonBlank(instanceId, "instanceId");
        managementService.recover(key, instanceId);
    }

    private static ServiceKey key(String namespace, String service) {
        requireNonBlank(namespace, "namespace");
        requireNonBlank(service, "service");
        return new ServiceKey(namespace, service);
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    /** HTTP 响应包装（租约/配置状态响应见 StatusController）。 */
    public record ListServicesResponse(List<ServiceView> services) {}

    public record EjectRequest(String reason) {}
}
