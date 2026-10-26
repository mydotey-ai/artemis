package org.mydotey.ai.artemis.management.server.http;

import com.google.gson.Gson;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.mydotey.ai.artemis.common.governance.EjectionScope;
import org.mydotey.ai.artemis.management.application.EjectionView;
import org.mydotey.ai.artemis.management.application.ManagementService;
import org.mydotey.ai.artemis.management.application.ServiceDetail;
import org.mydotey.ai.artemis.management.application.ServicePage;
import org.mydotey.ai.artemis.management.application.ServiceView;
import org.mydotey.ai.artemis.management.domain.AuditLog;
import org.mydotey.ai.artemis.management.domain.EjectionTarget;
import org.mydotey.ai.artemis.management.domain.Instance;
import org.mydotey.ai.artemis.management.domain.InstanceStatus;
import org.mydotey.ai.artemis.management.domain.LogicalInstance;
import org.mydotey.ai.artemis.management.domain.ServiceKey;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接入层：实例管理 Admin API（列表/详情 + 四级摘除/恢复 + 记录/审计查询）。
 * 契约见 docs/api/admin-api.md；契约级校验（空白参数、reason 长度、scope 组合）
 * 在信任边界一次完成。operator 取认证用户名（filter 透出）。
 */
@RestController
@RequestMapping("/admin/v1")
public final class AdminController {

    private static final int MAX_REASON_LENGTH = 1024;
    // 与 V3/V4 表列宽对齐（DB 态超长值会溢出列宽 503，契约层先拒绝保证双态行为一致）
    private static final int MAX_NAMESPACE_LENGTH = 255;
    private static final int MAX_SERVICE_LENGTH = 255;
    private static final int MAX_INSTANCE_ID_LENGTH = 512;
    // 逻辑实例 instanceId 为运维命名（非 SDK 生成），255 与 V4 唯一索引键长约束对齐（MySQL 3072 字节限）
    private static final int MAX_LOGICAL_INSTANCE_ID_LENGTH = 255;
    private static final int MAX_VALUE_LENGTH = 512;
    private static final int MAX_HOST_LENGTH = 255;
    private static final int MAX_METADATA_JSON_LENGTH = 16 * 1024;
    private static final int DEFAULT_LIST_LIMIT = 100;
    private static final int MAX_LIST_LIMIT = 1000;
    private static final Gson METADATA_JSON = new Gson();

    private final ManagementService managementService;

    public AdminController(ManagementService service) {
        this.managementService = service;
    }

    // ---- 服务视图 ----

    /** console 只读版：keyword 模糊 + 分页；offset 归零负数，limit 显式传入时 clamp 1..上界（缺省 0 = 全量，既有行为）。 */
    @GetMapping("/services")
    public ListServicesResponse listServices(
            @RequestParam(name = "namespace", required = false) String namespace,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            @RequestParam(name = "limit", defaultValue = "0") int limit) {
        ServicePage page = managementService.listServices(
                blankToNull(namespace), blankToNull(keyword), Math.max(offset, 0), boundLimit(limit));
        return new ListServicesResponse(page.services(), page.total());
    }

    @GetMapping("/services/{namespace}/{service}")
    public ServiceDetail getService(
            @PathVariable("namespace") String namespace,
            @PathVariable("service") String service,
            @RequestParam(name = "includeEjected", defaultValue = "false") boolean includeEjected,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            @RequestParam(name = "limit", defaultValue = "0") int limit) {
        return managementService.getService(
                key(namespace, service), includeEjected, Math.max(offset, 0), boundLimit(limit));
    }

    // ---- 四级摘除（资源导向统一端点，v0.4 phase-3）----

    @PostMapping("/ejections")
    public EjectionView eject(
            @RequestBody(required = false) EjectRequest request,
            @RequestAttribute(value = HttpBasicAuthFilter.AUTH_USER_ATTRIBUTE, required = false) String operator) {
        EjectionTarget target = toTarget(request);
        String reason = request.reason();
        if (reason != null && reason.codePointCount(0, reason.length()) > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("reason must be at most " + MAX_REASON_LENGTH + " characters");
        }
        return managementService.eject(target, reason, operator(operator));
    }

    @PostMapping("/ejections/recover")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recover(
            @RequestBody(required = false) EjectRequest request,
            @RequestAttribute(value = HttpBasicAuthFilter.AUTH_USER_ATTRIBUTE, required = false) String operator) {
        managementService.recover(toTarget(request), operator(operator));
    }

    @GetMapping("/ejections")
    public ListEjectionsResponse listEjections(
            @RequestParam(name = "activeOnly", defaultValue = "false") boolean activeOnly,
            @RequestParam(name = "scope", required = false) String scope,
            @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIST_LIMIT) int limit) {
        // 全量表只追加增长（review #5）：limit 上界约束单次响应（active 集小，无分页压力）
        int bounded = Math.min(Math.max(1, limit), MAX_LIST_LIMIT);
        return new ListEjectionsResponse(managementService.listEjections(
                activeOnly, scope == null || scope.isBlank() ? null : parseScope(scope), bounded));
    }

    // ---- 审计 ----

    @GetMapping("/audit-logs")
    public ListAuditLogsResponse auditLogs(
            @RequestParam(name = "operation", required = false) String operation,
            @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIST_LIMIT) int limit) {
        int bounded = Math.min(Math.max(1, limit), MAX_LIST_LIMIT);
        return new ListAuditLogsResponse(managementService.auditLogs(operation, bounded));
    }

    // ---- 逻辑实例（静态实例，v0.4 phase-4）----

    @PutMapping("/logical-instances")
    public LogicalInstanceResponse upsertLogicalInstance(
            @RequestBody(required = false) LogicalInstanceRequest request,
            @RequestAttribute(value = HttpBasicAuthFilter.AUTH_USER_ATTRIBUTE, required = false) String operator) {
        return toResponse(managementService.upsertLogicalInstance(toLogicalInstance(request), operator(operator)));
    }

    @DeleteMapping("/logical-instances")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLogicalInstance(
            @RequestParam("namespace") String namespace,
            @RequestParam("service") String service,
            @RequestParam("instanceId") String instanceId,
            @RequestAttribute(value = HttpBasicAuthFilter.AUTH_USER_ATTRIBUTE, required = false) String operator) {
        requireNonBlank(instanceId, "instanceId");
        requireLength(namespace, "namespace", MAX_NAMESPACE_LENGTH);
        requireLength(service, "service", MAX_SERVICE_LENGTH);
        requireLength(instanceId, "instanceId", MAX_LOGICAL_INSTANCE_ID_LENGTH);
        managementService.deleteLogicalInstance(key(namespace, service), instanceId, operator(operator));
    }

    @GetMapping("/logical-instances")
    public ListLogicalInstancesResponse listLogicalInstances(
            @RequestParam(name = "namespace", required = false) String namespace,
            @RequestParam(name = "service", required = false) String service,
            @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIST_LIMIT) int limit) {
        int bounded = Math.min(Math.max(1, limit), MAX_LIST_LIMIT);
        return new ListLogicalInstancesResponse(
                managementService.listLogicalInstances(blankToNull(namespace), blankToNull(service), bounded).stream()
                        .map(AdminController::toResponse)
                        .toList());
    }

    private static LogicalInstance toLogicalInstance(LogicalInstanceRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body required");
        }
        requireNonBlank(request.namespace(), "namespace");
        requireNonBlank(request.service(), "service");
        requireNonBlank(request.instanceId(), "instanceId");
        requireNonBlank(request.host(), "host");
        requireLength(request.namespace(), "namespace", MAX_NAMESPACE_LENGTH);
        requireLength(request.service(), "service", MAX_SERVICE_LENGTH);
        requireLength(request.instanceId(), "instanceId", MAX_LOGICAL_INSTANCE_ID_LENGTH);
        requireLength(request.host(), "host", MAX_HOST_LENGTH);
        int port = request.port() == null ? 0 : request.port(); // 缺省/显式 0 均拒（必填）
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be within 1..65535");
        }
        int weight = request.weight() == null ? 0 : request.weight(); // 缺省 = 未设置（归一 100）
        if (weight < 0) {
            throw new IllegalArgumentException("weight must be >= 0");
        }
        Map<String, String> metadata = request.metadata() == null ? Map.of() : request.metadata();
        if (metadata.values().stream().anyMatch(value -> value == null)) {
            throw new IllegalArgumentException("metadata values must not be null");
        }
        if (METADATA_JSON.toJson(metadata).length() > MAX_METADATA_JSON_LENGTH) {
            throw new IllegalArgumentException(
                    "metadata must serialize to at most " + MAX_METADATA_JSON_LENGTH + " characters");
        }
        return new LogicalInstance(
                key(request.namespace(), request.service()),
                new Instance(
                        request.instanceId(), request.host(), port, parseStatus(request.status()), weight, metadata));
    }

    /** status 缺省 UP；仅接受 UP/DOWN（UNSPECIFIED 归一在应用层，入口只认显式二值）。 */
    private static InstanceStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return InstanceStatus.UP;
        }
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "UP" -> InstanceStatus.UP;
            case "DOWN" -> InstanceStatus.DOWN;
            default -> throw new IllegalArgumentException("status must be UP or DOWN");
        };
    }

    private static LogicalInstanceResponse toResponse(LogicalInstance instance) {
        return new LogicalInstanceResponse(
                instance.service().namespace(),
                instance.service().service(),
                instance.instance().instanceId(),
                instance.instance().host(),
                instance.instance().port(),
                instance.instance().status().name(),
                instance.instance().weight(),
                instance.instance().metadata());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 0/缺省 = 全量；显式传入 clamp 1..MAX_LIST_LIMIT。 */
    private static int boundLimit(int limit) {
        return limit <= 0 ? 0 : Math.min(Math.max(1, limit), MAX_LIST_LIMIT);
    }

    // ---- 目标解析与校验 ----

    private static EjectionTarget toTarget(EjectRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body required");
        }
        EjectionScope scope = parseScope(request.scope());
        if (scope == EjectionScope.INSTANCE) {
            requireNonBlank(request.namespace(), "namespace");
            requireNonBlank(request.service(), "service");
            requireNonBlank(request.instanceId(), "instanceId");
            requireLength(request.namespace(), "namespace", MAX_NAMESPACE_LENGTH);
            requireLength(request.service(), "service", MAX_SERVICE_LENGTH);
            requireLength(request.instanceId(), "instanceId", MAX_INSTANCE_ID_LENGTH);
            return EjectionTarget.instance(key(request.namespace(), request.service()), request.instanceId());
        }
        requireNonBlank(request.value(), "value");
        requireLength(request.value(), "value", MAX_VALUE_LENGTH);
        return EjectionTarget.selector(scope, request.value());
    }

    private static EjectionScope parseScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return EjectionScope.INSTANCE; // 缺省实例级（v0.1 语义延续）
        }
        try {
            return EjectionScope.valueOf(scope.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("scope must be one of INSTANCE/SERVER/ZONE/GROUP");
        }
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

    private static void requireLength(String value, String name, int maxLength) {
        if (value.codePointCount(0, value.length()) > maxLength) {
            throw new IllegalArgumentException(name + " must be at most " + maxLength + " characters");
        }
    }

    /** 认证关闭（eval）时无认证用户名：anonymous 落审计。 */
    private static String operator(String operator) {
        return operator == null ? "anonymous" : operator;
    }

    /** HTTP 请求/响应包装。total = keyword 过滤后总数（v0.4 phase-6 起，additive）。 */
    public record ListServicesResponse(List<ServiceView> services, int total) {}

    /** 摘除/恢复请求体（四级一套：INSTANCE 级填 namespace/service/instanceId，selector 级填 value）。 */
    public record EjectRequest(
            String scope, String namespace, String service, String instanceId, String value, String reason) {}

    public record ListEjectionsResponse(List<EjectionView> records) {}

    public record ListAuditLogsResponse(List<AuditLog> logs) {}

    /** 逻辑实例 upsert 请求体（status 缺省 UP；weight ≤ 0 应用层归一为 100）。 */
    public record LogicalInstanceRequest(
            String namespace,
            String service,
            String instanceId,
            String host,
            Integer port,
            String status,
            Integer weight,
            Map<String, String> metadata) {}

    public record LogicalInstanceResponse(
            String namespace,
            String service,
            String instanceId,
            String host,
            int port,
            String status,
            int weight,
            Map<String, String> metadata) {}

    public record ListLogicalInstancesResponse(List<LogicalInstanceResponse> instances) {}
}
