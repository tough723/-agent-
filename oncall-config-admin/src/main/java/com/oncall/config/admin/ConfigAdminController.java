package com.oncall.config.admin;

import com.oncall.config.ConfigAuditLog;
import com.oncall.config.ConfigChange;
import com.oncall.config.ConfigRegistry;
import com.oncall.config.ConfigService;
import com.oncall.config.ConfigService.ConfigView;
import com.oncall.config.ConfigSpec;
import com.oncall.config.ValidationResult;
import com.oncall.config.schema.ConfigSchemaExporter;
import com.oncall.domain.governance.Operator;
import com.oncall.domain.governance.ReviewOutcome;
import com.oncall.domain.governance.ReviewRequest;
import com.oncall.domain.governance.ReviewVerdict;
import com.oncall.domain.governance.TwoPersonReview;

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
 * <p><b>所有 {@code @PathVariable} / {@code @RequestParam} 都显式写了名字</b>。
 * Spring MVC 默认靠反射读方法参数名，那需要编译时开 {@code -parameters}
 * （父 POM 已开，见 maven-compiler-plugin 的说明）。显式命名是第二道保险：
 * 万一哪天构建方式变了、标记丢了，这里不会退化成运行时 500。
 * 这个坑的表现很坏——编译通过、容器启动正常，第一个带路径参数的请求才炸。
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
    public ConfigItemView item(@PathVariable("key") String key) {
        ConfigSpec spec = visibleSpecOr404(key);
        ConfigView view = new ConfigView(spec, service.get(key),
                !service.get(key).equals(spec.defaultValue()));
        return ConfigItemView.of(view, policy.requiresSecondApproval(key));
    }

    @GetMapping("/items/{key}/history")
    public List<ConfigChange> history(@PathVariable("key") String key) {
        // 先确认这个键对调用方可见，否则等于通过历史接口枚举后端专属键
        visibleSpecOr404(key);
        return auditLog.history(key);
    }

    @GetMapping("/changes/recent")
    public List<ConfigChange> recent(@RequestParam(value = "limit", defaultValue = "20") int limit) {
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
    public ResponseEntity<ConfigWriteResponse> write(@PathVariable("key") String key,
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
    public ResponseEntity<ConfigWriteResponse> reset(@PathVariable("key") String key,
                                                     @RequestParam("reason") String reason,
                                                     @RequestHeader(name = HEADER_OPERATOR, required = false) String principal,
                                                     @RequestHeader(name = HEADER_ROLE, required = false) String role) {
        return write(key, new ConfigWriteRequest(null, reason), principal, role);
    }

    // ------------------------------------------------------- 双人复核

    /**
     * 复核通过一个待复核变更。
     *
     * <p><b>判定本身不在这里</b>——四条规则与它们的先后顺序全部委托给
     * {@link TwoPersonReview}（领域层）。这里只做三件事：
     * 取单子、把判定结果映射成 HTTP 状态码、清掉已失效的单子。
     *
     * <p>为什么必须委托而不是在这里再写一遍：工具白名单变更
     * （{@code oncall-tool-gateway}）走的是<b>同一套</b>复核规则。
     * 两处各写一遍一定会分叉，而「配置侧不允许自审、工具侧忘了这一条」
     * 在功能测试里完全看不出来——只有真的有人自审自批时才会暴露。
     *
     * <p><b>状态码映射</b>（四种拒绝刻意不同码，前端要给出不同的处置）：
     * <ul>
     *   <li>{@code EXPIRED} → 410 Gone：重新发起</li>
     *   <li>{@code NOT_AUTHORIZED} → 403 Forbidden：换人</li>
     *   <li>{@code SELF_APPROVAL} → 409 Conflict：换人</li>
     *   <li>{@code STALE} → 409 Conflict：重新发起</li>
     * </ul>
     */
    @PostMapping("/pending/{id}/confirm")
    public ConfigWriteResponse confirm(@PathVariable("id") String id,
                                       @RequestParam(value = "reason", required = false) String reason,
                                       @RequestHeader(name = HEADER_OPERATOR, required = false) String principal,
                                       @RequestHeader(name = HEADER_ROLE, required = false) String role) {
        Operator operator = requireAuthenticated(principal, role);
        PendingChange pending = requirePending(id);

        // 读当前值只进局部变量：只有判定为 STALE 时它才会出现在提示语里，
        // 而 TwoPersonReview 保证 STALE 只对「有权且非自审」的人产出。
        String current = service.get(pending.key());
        ReviewOutcome outcome = TwoPersonReview.evaluate(new ReviewRequest(
                "配置项 " + pending.key(),
                pending.requester(),
                pending.expiresAtMillis(),
                pending.oldValue(),
                current,
                operator), clock.getAsLong());

        if (!outcome.allowed()) {
            // 过期与失效的单子已经没意义了，删掉，避免被反复复核。
            // 「无权」与「自审」不删：那张单子本身是好的，换个人就该能复核。
            if (outcome.verdict() == ReviewVerdict.EXPIRED || outcome.verdict() == ReviewVerdict.STALE) {
                pendingStore.remove(id);
            }
            throw toHttpStatus(outcome);
        }

        // 放在判定之后：未通过复核的调用方不该通过 404/500 的差异
        // 探出这个键在注册表里的存在性。
        ConfigSpec spec = registry.require(pending.key());
        pendingStore.remove(id);
        String effectiveReason = (reason == null || reason.isBlank())
                ? pending.reason()
                : pending.reason() + " / 复核：" + reason.trim();
        return apply(spec, pending.newValue(), operator.principal(), effectiveReason, true);
    }

    /** 把领域层的判定映射成 HTTP 状态码。判定逻辑不在这里，映射才在。 */
    private static AdminApiException toHttpStatus(ReviewOutcome outcome) {
        return switch (outcome.verdict()) {
            case EXPIRED -> AdminApiException.gone(outcome.message());
            case NOT_AUTHORIZED -> AdminApiException.forbidden(outcome.message());
            case SELF_APPROVAL, STALE -> AdminApiException.conflict(outcome.message());
            case ALLOWED -> throw new IllegalStateException("ALLOWED 不该走到异常映射");
        };
    }

    /** 驳回一个待复核变更。发起人自己也可以驳回。 */
    @PostMapping("/pending/{id}/reject")
    public ResponseEntity<Void> reject(@PathVariable("id") String id,
                                       @RequestParam(value = "reason", required = false) String reason,
                                       @RequestHeader(name = HEADER_OPERATOR, required = false) String principal,
                                       @RequestHeader(name = HEADER_ROLE, required = false) String role) {
        Operator operator = requireAuthenticated(principal, role);
        PendingChange pending = requirePending(id);
        // 驳回走的是另一条路径（不经过双人复核判定），所以过期检查留在这里。
        // 过期单直接当不存在处理：留着它只会让人以为还能驳回。
        if (pending.isExpired(clock.getAsLong())) {
            pendingStore.remove(id);
            throw AdminApiException.gone("待复核单已过期，无需驳回");
        }
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

    /**
     * 取一张待复核单，不存在则 404。
     *
     * <p><b>刻意不在这里判过期</b>：过期是复核判定的一条规则，
     * 由 {@link TwoPersonReview} 统一负责。在这里先抛 410 会让
     * 「过期」这条规则有两个实现，而工具策略那侧只会有一个。
     */
    private PendingChange requirePending(String id) {
        return pendingStore.find(id)
                .orElseThrow(() -> AdminApiException.notFoundMessage("待复核单不存在：" + id));
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
