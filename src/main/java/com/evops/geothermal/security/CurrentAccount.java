package com.evops.geothermal.security;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.springframework.stereotype.Component;

/**
 * 当前登录账号解析器：从 Shiro Subject 取 {@link AccountPrincipal}。
 * 无 HTTP/Shiro 上下文（如直连 Service 的后台/测试调用）时回退到租户 1 管理员，
 * 保证既有写链路可用；REST 入口一律经过 authcBasic，必然是真实租户账号。
 */
@Component
public class CurrentAccount {

    private static final AccountPrincipal SYSTEM_FALLBACK =
            new AccountPrincipal("bootstrap", "内置管理员", 1L, "T1",
                    java.util.EnumSet.of(TenantRole.TENANT_ADMIN));

    public AccountPrincipal get() {
        try {
            Subject subject = SecurityUtils.getSubject();
            Object principal = subject.getPrincipal();
            if (principal instanceof AccountPrincipal) {
                return (AccountPrincipal) principal;
            }
        } catch (Exception ignored) {
            // 无 Shiro 环境（单元/集成测试直连服务层）
        }
        return SYSTEM_FALLBACK;
    }
}
