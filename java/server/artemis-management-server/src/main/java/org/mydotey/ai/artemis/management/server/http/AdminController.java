package org.mydotey.ai.artemis.management.server.http;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.mydotey.ai.artemis.common.governance.EjectionScope;
import org.mydotey.ai.artemis.management.application.EjectionView;
import org.mydotey.ai.artemis.management.application.ManagementService;
import org.mydotey.ai.artemis.management.application.ReleaseDiff;
import org.mydotey.ai.artemis.management.application.ServiceDetail;
import org.mydotey.ai.artemis.management.application.ServicePage;
import org.mydotey.ai.artemis.management.application.ServiceView;
import org.mydotey.ai.artemis.management.domain.AuditLog;
import org.mydotey.ai.artemis.management.domain.EjectionTarget;
import org.mydotey.ai.artemis.management.domain.Instance;
import org.mydotey.ai.artemis.management.domain.InstanceStatus;
import org.mydotey.ai.artemis.management.domain.LogicalInstance;
import org.mydotey.ai.artemis.management.domain.RouteRule;
import org.mydotey.ai.artemis.management.domain.RouteRuleRelease;
import org.mydotey.ai.artemis.management.domain.RouteRuleStatus;
import org.mydotey.ai.artemis.management.domain.RouteRuleTarget;
import org.mydotey.ai.artemis.management.domain.ServiceKey;
import org.mydotey.ai.artemis.management.domain.SubscriptionSource;
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
    // targets 序列化字节上界（MySQL TEXT 列 65535 字节防护，留余量）
    private static final int MAX_TARGETS_JSON_BYTES = 60 * 1024;
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

    // ---- 订阅关系（v0.5 phase-5，console 调用方视角组合视图）----

    /** discovery 多源并列（不跨源合并去重，源列即排障维度）；单源失败以 error 承载（§4.13）。 */
    @GetMapping("/subscriptions")
    public SubscriptionsResponse subscriptions() {
        return new SubscriptionsResponse(managementService.subscriptions());
    }

    public record SubscriptionsResponse(List<SubscriptionSource> sources) {}

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

    // ---- 路由规则（v0.5 phase-1，资源导向 CRUD；发布/release 归 phase-2）----

    @PostMapping("/route-rules")
    @ResponseStatus(HttpStatus.CREATED)
    public RouteRuleResponse createRouteRule(
            @RequestBody(required = false) RouteRuleRequest request,
            @RequestAttribute(value = HttpBasicAuthFilter.AUTH_USER_ATTRIBUTE, required = false) String operator) {
        return toResponse(managementService.createRouteRule(toRouteRule(request), operator(operator)));
    }

    @GetMapping("/route-rules")
    public ListRouteRulesResponse listRouteRules(
            @RequestParam(name = "namespace", required = false) String namespace,
            @RequestParam(name = "service", required = false) String service,
            @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIST_LIMIT) int limit) {
        int bounded = Math.min(Math.max(1, limit), MAX_LIST_LIMIT);
        return new ListRouteRulesResponse(
                managementService.listRouteRules(blankToNull(namespace), blankToNull(service), bounded).stream()
                        .map(AdminController::toResponse)
                        .toList());
    }

    @GetMapping("/route-rules/{namespace}/{service}/{name}")
    public RouteRuleResponse getRouteRule(
            @PathVariable("namespace") String namespace,
            @PathVariable("service") String service,
            @PathVariable("name") String name) {
        return toResponse(managementService.getRouteRule(key(namespace, service), name));
    }

    @PutMapping("/route-rules/{namespace}/{service}/{name}")
    public RouteRuleResponse updateRouteRule(
            @PathVariable("namespace") String namespace,
            @PathVariable("service") String service,
            @PathVariable("name") String name,
            @RequestBody(required = false) RouteRuleRequest request,
            @RequestAttribute(value = HttpBasicAuthFilter.AUTH_USER_ATTRIBUTE, required = false) String operator) {
        RouteRule rule = toRouteRule(request);
        // 路径定位键不可改：body 与路径不一致直接拒绝（防静默改键）。
        // name 用 Objects.equals：body 缺 name 时为 null，equals 方向反了会 NPE 成 500
        if (!rule.service().equals(key(namespace, service)) || !Objects.equals(rule.name(), name)) {
            throw new IllegalArgumentException("namespace/service/name must match the request path");
        }
        return toResponse(managementService.updateRouteRule(rule, operator(operator)));
    }

    @DeleteMapping("/route-rules/{namespace}/{service}/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRouteRule(
            @PathVariable("namespace") String namespace,
            @PathVariable("service") String service,
            @PathVariable("name") String name,
            @RequestAttribute(value = HttpBasicAuthFilter.AUTH_USER_ATTRIBUTE, required = false) String operator) {
        managementService.deleteRouteRule(key(namespace, service), name, operator(operator));
    }

    // ---- canary（v0.5 phase-4：route-rule + release 预置派生，一键金丝雀切分）----

    /** canary 一键部署：selector 圈金丝雀实例 + weight（0–10000）承接权重 → 派生保留名 canary
     *  规则（canary 目标 + 兜底目标）并发布；查询/历史/回滚经既有 route-rule API（规则名 canary）。 */
    @PostMapping("/canary/{namespace}/{service}")
    public RouteRuleResponse deployCanary(
            @PathVariable("namespace") String namespace,
            @PathVariable("service") String service,
            @RequestBody(required = false) CanaryRequest request,
            @RequestAttribute(value = HttpBasicAuthFilter.AUTH_USER_ATTRIBUTE, required = false) String operator) {
        if (request == null || request.weight() == null) {
            throw new IllegalArgumentException("selector and weight required");
        }
        return toResponse(managementService.deployCanary(
                key(namespace, service), request.selector(), request.weight(), operator(operator)));
    }

    /** 结束灰度：删除 canary 规则（无治理意图 = 全实例回归可见），幂等。 */
    @DeleteMapping("/canary/{namespace}/{service}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCanary(
            @PathVariable("namespace") String namespace,
            @PathVariable("service") String service,
            @RequestAttribute(value = HttpBasicAuthFilter.AUTH_USER_ATTRIBUTE, required = false) String operator) {
        managementService.deleteCanary(key(namespace, service), operator(operator));
    }

    // ---- 两段式发布（v0.5 phase-2：release / rollback / diff / 发布记录）----

    /** 发布：一个 API 完成灰度运维意图（编辑列拷贝生效列，落 append-only 发布记录）。 */
    @PostMapping("/route-rules/{namespace}/{service}/{name}/release")
    public RouteRuleResponse releaseRouteRule(
            @PathVariable("namespace") String namespace,
            @PathVariable("service") String service,
            @PathVariable("name") String name,
            @RequestAttribute(value = HttpBasicAuthFilter.AUTH_USER_ATTRIBUTE, required = false) String operator) {
        return toResponse(managementService.releaseRouteRule(key(namespace, service), name, operator(operator)));
    }

    /** 一键回滚：指向历史版本的反向 release（body 指定源发布记录 id）。 */
    @PostMapping("/route-rules/{namespace}/{service}/{name}/rollback")
    public RouteRuleResponse rollbackRouteRule(
            @PathVariable("namespace") String namespace,
            @PathVariable("service") String service,
            @PathVariable("name") String name,
            @RequestBody(required = false) RollbackRequest request,
            @RequestAttribute(value = HttpBasicAuthFilter.AUTH_USER_ATTRIBUTE, required = false) String operator) {
        if (request == null || request.releaseId() == null) {
            throw new IllegalArgumentException("releaseId required");
        }
        return toResponse(managementService.rollbackRouteRule(
                key(namespace, service), name, request.releaseId(), operator(operator)));
    }

    /** 发布差异预览：缺省 = 编辑列 vs 当前生效列；releaseId = 历史快照 vs 当前生效列（回滚预览）。 */
    @GetMapping("/route-rules/{namespace}/{service}/{name}/diff")
    public ReleaseDiffResponse routeRuleDiff(
            @PathVariable("namespace") String namespace,
            @PathVariable("service") String service,
            @PathVariable("name") String name,
            @RequestParam(name = "releaseId", required = false) Long releaseId) {
        return toResponse(managementService.routeRuleDiff(key(namespace, service), name, releaseId));
    }

    /** 发布记录列表（回滚历史源，id 降序最新在前）。 */
    @GetMapping("/route-rules/{namespace}/{service}/{name}/releases")
    public ListReleasesResponse listReleases(
            @PathVariable("namespace") String namespace,
            @PathVariable("service") String service,
            @PathVariable("name") String name,
            @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIST_LIMIT) int limit) {
        int bounded = Math.min(Math.max(1, limit), MAX_LIST_LIMIT);
        return new ListReleasesResponse(managementService.listReleases(key(namespace, service), name, bounded).stream()
                .map(AdminController::toResponse)
                .toList());
    }

    /** 请求体 → 领域规则（域约束校验在用例层 RouteRules.validate，此处只组装与解析缺省值）。
     * 两段式：编辑通道不收生效值（weight 恒 0 组装；create 初始归零、update 应用层按
     * selector 等值继承）——生效列唯一写入口是 release / rollback。 */
    private static RouteRule toRouteRule(RouteRuleRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body required");
        }
        if (request.targets() == null || request.targets().isEmpty()) {
            throw new IllegalArgumentException("targets required");
        }
        List<RouteRuleTarget> targets = new ArrayList<>(request.targets().size());
        for (RouteRuleTargetRequest target : request.targets()) {
            if (target == null) {
                throw new IllegalArgumentException("target required");
            }
            Map<String, String> selector = target.selector() == null ? Map.of() : target.selector(); // 空 map = 兜底目标
            targets.add(new RouteRuleTarget(
                    selector, 0, target.unreleasedWeight() == null ? 0 : target.unreleasedWeight()));
        }
        // 按 UTF-8 字节数设限：MySQL TEXT 列 65535 字节上限，字符计数会放行 4 字节字符溢出（503 而非 400）
        if (METADATA_JSON.toJson(targets).getBytes(StandardCharsets.UTF_8).length > MAX_TARGETS_JSON_BYTES) {
            throw new IllegalArgumentException(
                    "targets must serialize to at most " + MAX_TARGETS_JSON_BYTES + " bytes");
        }
        return new RouteRule(
                key(request.namespace(), request.service()),
                request.name(),
                parseRouteRuleStatus(request.status()),
                List.copyOf(targets),
                null,
                null,
                null); // operator/时间戳由应用层填写
    }

    /** status 缺省 ACTIVE；仅接受 ACTIVE/INACTIVE。 */
    private static RouteRuleStatus parseRouteRuleStatus(String status) {
        if (status == null || status.isBlank()) {
            return RouteRuleStatus.ACTIVE;
        }
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "ACTIVE" -> RouteRuleStatus.ACTIVE;
            case "INACTIVE" -> RouteRuleStatus.INACTIVE;
            default -> throw new IllegalArgumentException("status must be ACTIVE or INACTIVE");
        };
    }

    private static RouteRuleResponse toResponse(RouteRule rule) {
        return new RouteRuleResponse(
                rule.service().namespace(),
                rule.service().service(),
                rule.name(),
                rule.status().name(),
                rule.targets().stream()
                        .map(target -> new RouteRuleTargetResponse(
                                target.selector(), target.weight(), target.unreleasedWeight()))
                        .toList(),
                rule.operator(),
                rule.createdAt(),
                rule.updatedAt());
    }

    private static ReleaseDiffResponse toResponse(ReleaseDiff diff) {
        return new ReleaseDiffResponse(
                diff.service().namespace(),
                diff.service().service(),
                diff.name(),
                diff.baseReleaseId(),
                diff.targets().stream()
                        .map(target ->
                                new TargetDiffResponse(target.selector(), target.currentWeight(), target.nextWeight()))
                        .toList());
    }

    private static RouteRuleReleaseResponse toResponse(RouteRuleRelease release) {
        return new RouteRuleReleaseResponse(
                release.id(),
                release.service().namespace(),
                release.service().service(),
                release.name(),
                release.type().name(),
                release.rollbackTo(),
                release.snapshot().stream()
                        .map(target -> new RouteRuleTargetResponse(
                                target.selector(), target.weight(), target.unreleasedWeight()))
                        .toList(),
                release.operator(),
                release.releasedAt());
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

    /** 路由规则创建/替换请求体（status 缺省 ACTIVE；selector 空 map = 兜底目标；
     * targets 只收编辑列——生效列经 release/rollback 变更，两段式）。 */
    public record RouteRuleRequest(
            String namespace, String service, String name, String status, List<RouteRuleTargetRequest> targets) {}

    public record RouteRuleTargetRequest(Map<String, String> selector, Integer unreleasedWeight) {}

    /** 回滚请求体：releaseId = 源发布记录 id（回滚历史源）。 */
    public record RollbackRequest(Long releaseId) {}

    /** canary 部署请求体：selector 圈金丝雀实例（非空 metadata 等值）、weight 0–10000 承接权重。 */
    public record CanaryRequest(Map<String, String> selector, Integer weight) {}

    /** 发布差异预览响应：currentWeight/nextWeight null = 该侧无此目标（移除/新增）。 */
    public record ReleaseDiffResponse(
            String namespace, String service, String name, Long baseReleaseId, List<TargetDiffResponse> targets) {}

    public record TargetDiffResponse(Map<String, String> selector, Integer currentWeight, Integer nextWeight) {}

    public record RouteRuleReleaseResponse(
            long id,
            String namespace,
            String service,
            String name,
            String type,
            Long rollbackTo,
            List<RouteRuleTargetResponse> snapshot,
            String operator,
            Instant releasedAt) {}

    public record ListReleasesResponse(List<RouteRuleReleaseResponse> releases) {}

    public record RouteRuleResponse(
            String namespace,
            String service,
            String name,
            String status,
            List<RouteRuleTargetResponse> targets,
            String operator,
            Instant createdAt,
            Instant updatedAt) {}

    public record RouteRuleTargetResponse(Map<String, String> selector, int weight, int unreleasedWeight) {}

    public record ListRouteRulesResponse(List<RouteRuleResponse> rules) {}
}
