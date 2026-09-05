package com.oncall.config.admin;

import com.oncall.config.ConfigAuditLog;
import com.oncall.config.ConfigChange;
import com.oncall.config.ConfigRegistry;
import com.oncall.config.ConfigService;
import com.oncall.config.ConfigService.ConfigView;
import com.oncall.config.ConfigSpec;
import com.oncall.config.ValidationResult;
import com.oncall.config.schema.ConfigSchemaExporter;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 配置治理的 REST 接口。
 *
 * <p>路径刻意用 {@code /items/{key}} 而不是 {@code /{key}}：
 * 后者会与 {@code /views}、{@code /schema} 这类字面量路径产生模板匹配歧义。
 * Spring 虽然规定字面量优先，但依赖这个优先级会让读代码的人每次都要想一遍。
 *
 * <p><b>身份从哪来</b>：{@code X-Operator} / {@code X-Operator-Role} 两个请求头
 * 必须由网关在鉴权后写入，并且<b>网关要剥掉客户端自带的这两个头</b>。
 * 否则前端自己填一个 {@code ADMIN} 就提权了。这一层无法自证，
 * 所以写成常量放在这里提醒，接入时必须核对。
 */
@RestController
@RequestMapping("/api/config")
public class ConfigAdminController {

    /** 网关必须剥掉并重写的请求头。见类注释。 */
    public static final String HEADER_OPERATOR = "X-Operator";
    public static final String HEADER_ROLE = "X-Operator-Role";

    private final ConfigService service;
    private final ConfigRegistry registry;
    private final ConfigSchemaExporter schemaExporter;
    private final ConfigAuditLog auditLog;
    private final PendingChangeStore pendingStore;
    private final ConfigAccessPolicy policy;
    private final LongSupplier clock;

    public ConfigAdminController(ConfigService service,
                                 ConfigRegistry registry,
                                 ConfigSchemaExporter schemaExporter,
                                 ConfigAuditLog auditLog,
                                 PendingChangeStore pendingStore,
                                 ConfigAccessPolicy policy) {
        this(service, registry, schemaExporter, auditLog, pendingStore, policy,
                System::currentTimeMillis);
    }

    /** 可注入时钟，用于确定性地测试待复核单过期。 */
    public ConfigAdminController(ConfigService service,
                                 ConfigRegistry registry,
                                 ConfigSchemaExporter schemaExporter,
                                 ConfigAuditLog auditLog,
                                 PendingChangeStore pendingStore,
                                 ConfigAccessPolicy policy,
                                 LongSupplier clock) {
        this.service = service;
        this.registry = registry;
        this.schemaExporter = schemaExporter;
        this.auditLog = auditLog;
        this.pendingStore = pendingStore;
        this.policy = policy;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 读

    /** 前端渲染表单用：全部可见配置项 + 当前生效值 + 是否被改离默认。 */
    @GetMapping("/views")
    public List<ConfigItemView> views() {
        return service.viewsForUi().stream()
                .map(v -> ConfigItemView.of(v, policy.requiresSecondApproval(v.spec().key())))
                .toList();
    }

    /**
     * 前端表单的 JSON schema。
     *
     * <p>单独一个端点而不是并进 {@code /views}：schema 只在页面加载时需要一次，
     * 值则可能轮询。分开可以让 schema 走强缓存。
     */
    @GetMapping(value = "/schema", produces = MediaType.APPLICATION_JSON_VALUE)
    public String schema() {
        return schemaExporter.exportForUi();
    }

    @GetMapping("/items/{key}")
    public ConfigItemView item(@PathVariable String key) {
        ConfigSpec spec = visibleSpecOr404(key);
        ConfigView view = new ConfigView(spec, service.get(key),
                !service.get(key).equals(spec.defaultValue()));
        return ConfigItemView.of(view, policy.requiresSecondApproval(key));
    }

    @GetMapping("/items/{key}/history")
    public List<ConfigChange> history(@PathVariable String key) {
        // 先确认这个键对调用方可见，否则等于通过历史接口枚举后端专属键
        visibleSpecOr404(key);
        return auditLog.history(key);
    }

    @GetMapping("/changes/recent")
    public List<ConfigChange> recent(@RequestParam(defaultValue = "20") int limit) {
        if (limit <= 0 || limit > 500) {
            throw AdminApiException.badRequest("limit 必须在 1 到 500 之间");
        }
        return auditLog.recent(limit);
    }

    // ------------------------------------------------------------------ 写

    /**
     * 写入一个配置值。
     *
     * <p>三种结果：
     * <ul>
     *   <li>200 已生效（普通项，操作者有写权限）</li>
     *   <li>202 已生成待复核单（高危项）</li>
     *   <li>400 校验不通过 / 缺变更理由</li>
     * </ul>
     */
    @PutMapping("/items/{key}")
    public ResponseEntity<ConfigWriteResponse> write(@PathVariable String key,
                                                     @RequestBody ConfigWriteRequest body,
                                                     @RequestHeader(name = HEADER_OPERATOR, required = false) String principal,
                                                     @RequestHeader(name = HEADER_ROLE, required = false) String role) {
        Operator operator = requireAuthenticated(principal, role);
        ConfigSpec spec = visibleSpecOr404(key);
        if (!body.hasReason()) {
            throw AdminApiException.badRequest("变更理由必填——没有理由的变更无法审计");
        }

        String oldValue = service.get(key);
        boolean reset = body.isResetRequest();
        String target = reset ? null : body.value().trim();
        boolean highRisk = policy.requiresSecondApproval(key);

        if (isUnchanged(spec, oldValue, target)) {
            // 不写库、不审计，但仍要明确告知，避免前端以为改成功了
            return ResponseEntity.ok(ConfigWriteResponse.unchanged(key, oldValue, highRisk));
        }

        if (highRisk) {
            if (!policy.canPropose(operator, spec)) {
                throw AdminApiException.forbidden("没有修改该配置项的权限");
            }
            long now = clock.getAsLong();
            PendingChange pending = new PendingChange(
                    UUID.randomUUID().toString(), key, target,
                    operator.principal(), body.reason().trim(), oldValue,
                    now, now + ConfigAccessPolicy.PENDING_TTL_MILLIS);
            pendingStore.put(pending);
            return ResponseEntity.accepted()
                    .body(ConfigWriteResponse.pending(pending, true));
        }

        if (!policy.canWriteDirectly(operator, spec)) {
            throw AdminApiException.forbidden("没有修改该配置项的权限");
        }
        return ResponseEntity.ok(apply(spec, target, operator.principal(), body.reason().trim(), highRisk));
    }

    /** 恢复默认值。理由走查询参数——DELETE 带请求体在很多代理上会被丢弃。 */
    @DeleteMapping("/items/{key}")
    public ResponseEntity<ConfigWriteResponse> reset(@PathVariable String key,
                                                     @RequestParam String reason,
                                                     @RequestHeader(name = HEADER_OPERATOR, required = false) String principal,
                                                     @RequestHeader(name = HEADER_ROLE, required = false) String role) {
        return write(key, new ConfigWriteRequest(null, reason), principal, role);
    }

    // ------------------------------------------------------- 双人复核

    /**
     * 复核通过一个待复核变更。
     *
     * <p>三道检查，缺一不可：
     * <ol>
     *   <li>复核者必须是 ADMIN；</li>
     *   <li><b>复核者不能是发起人</b>——否则"双人"就是同一个人点两次；</li>
     *   <li>该键在发起后没被别人改过——否则复核者判断的依据已经失效。</li>
     * </ol>
     */
    @PostMapping("/pending/{id}/confirm")
    public ConfigWriteResponse confirm(@PathVariable String id,
                                       @RequestParam(required = false) String reason,
                                       @RequestHeader(name = HEADER_OPERATOR, required = false) String principal,
                                       @RequestHeader(name = HEADER_ROLE, required = false) String role) {
        Operator operator = requireAuthenticated(principal, role);
        PendingChange pending = livePendingOrThrow(id);

        if (!policy.canApprove(operator)) {
            throw AdminApiException.forbidden("复核高危配置变更需要 ADMIN 权限");
        }
        if (pending.requester().equals(operator.principal())) {
            throw AdminApiException.conflict("不能复核自己发起的变更——双人复核的意义在于两个独立的判断");
        }
        ConfigSpec spec = registry.require(pending.key());
        String current = service.get(pending.key());
        if (pending.staleAgainst(current)) {
            pendingStore.remove(id);
            throw AdminApiException.conflict(
                    "该配置项在发起复核后已被其他人修改（发起时 " + pending.oldValue()
                            + "，现在 " + current + "），本次复核已失效，请重新发起");
        }

        pendingStore.remove(id);
        String effectiveReason = (reason == null || reason.isBlank())
                ? pending.reason()
                : pending.reason() + " / 复核：" + reason.trim();
        return apply(spec, pending.newValue(), operator.principal(), effectiveReason, true);
    }

    /** 驳回一个待复核变更。发起人自己也可以驳回。 */
    @PostMapping("/pending/{id}/reject")
    public ResponseEntity<Void> reject(@PathVariable String id,
                                       @RequestParam(required = false) String reason,
                                       @RequestHeader(name = HEADER_OPERATOR, required = false) String principal,
                                       @RequestHeader(name = HEADER_ROLE, required = false) String role) {
        Operator operator = requireAuthenticated(principal, role);
        PendingChange pending = livePendingOrThrow(id);
        if (!policy.canApprove(operator) && !pending.requester().equals(operator.principal())) {
            throw AdminApiException.forbidden("只有复核人或发起人可以驳回");
        }
        pendingStore.remove(id);
        return ResponseEntity.noContent().build();
    }

    /** 强制从底层存储重新加载配置。 */
    @PostMapping("/reload")
    public ResponseEntity<Void> reload(@RequestHeader(name = HEADER_OPERATOR, required = false) String principal,
                                       @RequestHeader(name = HEADER_ROLE, required = false) String role) {
        Operator operator = requireAuthenticated(principal, role);
        if (operator.role() == Operator.Role.VIEWER) {
            throw AdminApiException.forbidden("只有具备写权限的人可以触发配置重载");
        }
        service.refresh();
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------- 内部

    private ConfigWriteResponse apply(ConfigSpec spec, String target, String operator,
                                      String reason, boolean highRisk) {
        String oldValue = service.get(spec.key());
        ValidationResult vr = (target == null)
                ? service.reset(spec.key(), operator, reason, true)
                : service.set(spec.key(), target, operator, reason, true);
        if (!vr.valid()) {
            throw AdminApiException.badRequest(vr.message());
        }
        return ConfigWriteResponse.applied(spec.key(), oldValue, service.get(spec.key()), highRisk);
    }

    private PendingChange livePendingOrThrow(String id) {
        PendingChange pending = pendingStore.find(id)
                .orElseThrow(() -> AdminApiException.notFoundMessage("待复核单不存在：" + id));
        if (pending.isExpired(clock.getAsLong())) {
            pendingStore.remove(id);
            throw AdminApiException.gone("待复核单已过期，请重新发起——"
                    + "隔了这么久，系统状态可能已经变了，当时的判断不再成立");
        }
        return pending;
    }

    /**
     * 取一个对调用方可见的声明。
     *
     * <p>BACKEND_ONLY 与未声明的键走<b>同一条</b> 404 分支，理由见
     * {@link AdminApiException#notFound(String)}。
     */
    private ConfigSpec visibleSpecOr404(String key) {
        ConfigSpec spec = registry.find(key).orElseThrow(() -> AdminApiException.notFound(key));
        if (!policy.isVisible(spec)) {
            throw AdminApiException.notFound(key);
        }
        return spec;
    }

    private static boolean isUnchanged(ConfigSpec spec, String oldValue, String target) {
        if (target == null) {
            return oldValue.equals(spec.defaultValue());
        }
        return target.equals(oldValue);
    }

    private static Operator requireAuthenticated(String principal, String role) {
        Operator operator = Operator.fromHeaders(principal, role);
        if (operator.isAnonymous()) {
            throw AdminApiException.unauthorized("缺少操作人身份");
        }
        return operator;
    }
}
