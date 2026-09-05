package com.oncall.config.admin;

import java.util.Locale;
import java.util.Objects;

/**
 * 发起配置操作的人。
 *
 * <p>生产环境应由网关从 JWT / SSO 解出后写入请求头，<b>不能由前端自行声明</b>——
 * 否则任何人都可以把 {@code X-Operator-Role} 填成 ADMIN。本模块只负责读取与判定，
 * 身份的<b>可信来源</b>是部署边界的责任，这一点必须在接入时明确（见类末的说明）。
 *
 * @param principal 操作人标识，会原样写进审计日志
 * @param role      角色
 */
public record Operator(String principal, Role role) {

    public Operator {
        if (principal == null || principal.isBlank()) {
            throw new IllegalArgumentException("operator principal 不能为空");
        }
        Objects.requireNonNull(role, "role");
    }

    /** 匿名请求：只读，且看不到任何配置项。用于未通过鉴权的探测请求。 */
    public static Operator anonymous() {
        return new Operator("anonymous", Role.VIEWER);
    }

    /** 从请求头解析。解析失败返回 {@link #anonymous()} 而不是抛异常——由调用方决定 401。 */
    public static Operator fromHeaders(String principal, String role) {
        if (principal == null || principal.isBlank()) {
            return anonymous();
        }
        Role r;
        try {
            r = Role.valueOf(role == null ? "" : role.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // 无法识别的角色按最低权限处理，不给默认 EDITOR
            r = Role.VIEWER;
        }
        return new Operator(principal.trim(), r);
    }

    public boolean isAnonymous() {
        return "anonymous".equals(principal);
    }

    /**
     * 角色。
     *
     * <p>刻意只有三级，且<b>没有"超级管理员"</b>：高危配置需要的是
     * 「另一个人也同意」，不是「某个人的权限更大」。加一个超级角色
     * 只会让双人复核形同虚设。
     */
    public enum Role {
        /** 只读。 */
        VIEWER,
        /** 可改普通配置项。 */
        EDITOR,
        /** 可发起并复核高危配置项变更。 */
        ADMIN
    }
}
