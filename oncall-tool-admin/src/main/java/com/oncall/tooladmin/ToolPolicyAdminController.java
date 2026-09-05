package com.oncall.tooladmin;

import com.oncall.domain.governance.Operator;
import com.oncall.toolgateway.ToolPolicyEngine;
import com.oncall.toolgateway.ToolPolicyGovernance;
import com.oncall.toolgateway.governance.ToolPolicyChange;
import com.oncall.toolgateway.governance.ToolPolicyChangeAudit;
import com.oncall.toolgateway.governance.ToolPolicyChangeTicket;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工具白名单治理的 REST 接口。
 *
 * <p><b>这一层不含任何判定逻辑。</b>
 * 「要不要两个人」「能不能自审」「过期了怎么办」全部在
 * {@link ToolPolicyGovernance} 与 {@code TwoPersonReview} 里。
 * 这里只做四件事：解身份、解请求体、调用治理层、把结果映射成 HTTP。
 *
 * <p>这么切是有代价的（多一层），但收益是<b>判定只有一份</b>：
 * 配置侧与工具侧共用 {@code TwoPersonReview}，
 * 而 HTTP 映射两边各自做——映射错了是界面问题，判定错了是安全问题。
 *
 * <p><b>身份从哪来</b>：{@code X-Operator} / {@code X-Operator-Role}
 * 必须由网关在鉴权后写入，并且<b>网关要剥掉客户端自带的这两个头</b>。
 * 否则前端自己填一个 {@code ADMIN} 就获得了复核权。
 * 这一层无法自证，写成常量放在这里提醒，接入时必须核对
 * （与 {@code ConfigAdminController} 的约定完全一致）。
 *
 * <p><b>所有 {@code @PathVariable} / {@code @RequestParam} 都显式写了名字</b>：
 * 依赖反射读参数名需要编译期开 {@code -parameters}，
 * 显式命名是第二道保险。这个坑的表现很坏——编译通过、容器启动正常，
 * 第一个带路径参数的请求才炸。
 */
@RestController
@RequestMapping("/api/tools")
public class ToolPolicyAdminController {

    /** 网关必须剥掉并重写的请求头。见类注释。 */
    public static final String HEADER_OPERATOR = "X-Operator";
    public static final String HEADER_ROLE = "X-Operator-Role";

    private final ToolPolicyEngine engine;
    private final ToolPolicyGovernance governance;
    private final ToolPolicyChangeAudit audit;

    public ToolPolicyAdminController(ToolPolicyEngine engine,
                                     ToolPolicyGovernance governance,
                                     ToolPolicyChangeAudit audit) {
        this.engine = engine;
        this.governance = governance;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ 读

    /**
     * 当前白名单。
     *
     * <p>管理员必须能看到"现在到底放行了哪些工具"——
     * 否则双人复核就是闭着眼睛签字。顺序由引擎保证（按工具名）。
     */
    @GetMapping("/policies")
    public List<ToolPolicyView> policies(@RequestHeader(name = HEADER_OPERATOR, required = false) String principal,
                                         @RequestHeader(name = HEADER_ROLE, required = false) String role) {
        requireAuthenticated(principal, role);
        return engine.all().stream().map(ToolPolicyView::of).toList();
    }

    /** 未决的待复核单。 */
    @GetMapping("/pending")
    public List<ToolPolicyTicketView> pending(@RequestHeader(name = HEADER_OPERATOR, required = false) String principal,
                                              @RequestHeader(name = HEADER_ROLE, required = false) String role) {
        requireAuthenticated(principal, role);
        return governance.openTickets().stream().map(ToolPolicyTicketView::of).toList();
    }

    /**
     * 变更审计：谁在什么时候放行/收紧了什么。
     *
     * <p>被拒的尝试也在里面——「谁试过放行什么」同样是审计对象。
     */
    @GetMapping("/audit")
    public List<ToolPolicyChangeAudit.Entry> audit(
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestHeader(name = HEADER_OPERATOR, required = false) String principal,
            @RequestHeader(name = HEADER_ROLE, required = false) String role) {
        requireAuthenticated(principal, role);
        if (limit <= 0 || limit > 500) {
            throw ToolAdminApiException.badRequest("limit 必须在 1 到 500 之间");
        }
        return audit.recent(limit);
    }

    /**
     * 预演一次变更的风险方向，<b>不落地</b>。
     *
     * <p>给界面在提交前提示"这需要两个人，因为……"。
     * 没有它的话，发起人只能提交之后才知道要走复核流程——
     * 而那一次提交已经在审计里留下了一条 PROPOSED。
     */
    @PostMapping("/policies/preview")
    public PreviewResponse preview(@RequestBody ToolPolicyChangeRequest body,
                                   @RequestHeader(name = HEADER_OPERATOR, required = false) String principal,
                                   @RequestHeader(name = HEADER_ROLE, required = false) String role) {
        requireAuthenticated(principal, role);
        var delta = governance.preview(body.toChange());
        return new PreviewResponse(delta.widens(), delta.reasons());
    }

    // ------------------------------------------------------------------ 写

    /**
     * 发起一次变更。
     *
     * <p>两种结果：
     * <ul>
     *   <li>200 已生效（方向为收紧，不需要第二个人）</li>
     *   <li>202 已生成待复核单（方向为放宽）</li>
     * </ul>
     */
    @PostMapping("/policies")
    public ResponseEntity<ToolPolicyWriteResponse> change(
            @RequestBody ToolPolicyChangeRequest body,
            @RequestHeader(name = HEADER_OPERATOR, required = false) String principal,
            @RequestHeader(name = HEADER_ROLE, required = false) String role) {
        Operator operator = requireAuthenticated(principal, role);
        ToolPolicyChange change = body.toChange();
        if (body.reason() == null || body.reason().isBlank()) {
            throw ToolAdminApiException.badRequest("变更理由必填——没有理由的变更无法审计，复核人也无从判断");
        }

        var proposal = governance.propose(change, operator, body.reason().trim());
        var response = new ToolPolicyWriteResponse(
                change.toolName(),
                proposal.appliedDirectly(),
                !proposal.appliedDirectly(),
                proposal.ticket() == null ? null : proposal.ticket().id(),
                proposal.delta().reasons());
        return proposal.appliedDirectly()
                ? ResponseEntity.ok(response)
                : ResponseEntity.accepted().body(response);
    }

    /** 复核通过。四种拒绝映射到不同状态码，见 {@link ToolAdminExceptionHandler}。 */
    @PostMapping("/pending/{id}/confirm")
    public ToolPolicyWriteResponse confirm(
            @PathVariable("id") String id,
            @RequestParam(value = "reason", required = false) String reason,
            @RequestHeader(name = HEADER_OPERATOR, required = false) String principal,
            @RequestHeader(name = HEADER_ROLE, required = false) String role) {
        Operator operator = requireAuthenticated(principal, role);
        ToolPolicyChangeTicket ticket = governance.confirm(id, operator,
                reason == null ? null : reason.trim());
        return new ToolPolicyWriteResponse(
                ticket.change().toolName(), true, false, null,
                List.of("复核通过：" + operator.principal()
                        + (reason == null || reason.isBlank() ? "" : " / " + reason.trim())));
    }

    /** 驳回。发起人自己或 ADMIN 都可以。 */
    @PostMapping("/pending/{id}/reject")
    public ResponseEntity<Void> reject(
            @PathVariable("id") String id,
            @RequestParam(value = "reason", required = false) String reason,
            @RequestHeader(name = HEADER_OPERATOR, required = false) String principal,
            @RequestHeader(name = HEADER_ROLE, required = false) String role) {
        Operator operator = requireAuthenticated(principal, role);
        governance.reject(id, operator, reason);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ 内部

    private static Operator requireAuthenticated(String principal, String role) {
        Operator operator = Operator.fromHeaders(principal, role);
        if (operator.isAnonymous()) {
            throw ToolAdminApiException.unauthorized("缺少操作人身份");
        }
        return operator;
    }

    /**
     * 预演结果。
     *
     * @param widens  是否会放宽权限（true ⇒ 需要第二人复核）
     * @param reasons 判定理由，界面要原样展示给发起人
     */
    public record PreviewResponse(boolean widens, List<String> reasons) {
    }
}
