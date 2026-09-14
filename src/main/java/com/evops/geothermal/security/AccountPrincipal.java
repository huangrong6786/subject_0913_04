package com.evops.geothermal.security;

import java.util.Collections;
import java.util.Set;

/**
 * 认证主体（Shiro Principal）：账号、所属租户与角色集合。
 * 数据权限层据此在每次查询时施加租户隔离与角色授权收敛。
 */
public class AccountPrincipal {

    public static final long SYSTEM_TENANT_ID = 1L;

    private final String account;
    private final String displayName;
    private final Long tenantId;
    private final String tenantCode;
    private final Set<TenantRole> roles;

    public AccountPrincipal(String account, String displayName, Long tenantId, String tenantCode,
                            Set<TenantRole> roles) {
        this.account = account;
        this.displayName = displayName;
        this.tenantId = tenantId;
        this.tenantCode = tenantCode;
        this.roles = roles == null ? Collections.emptySet() : Collections.unmodifiableSet(roles);
    }

    public String getAccount() { return account; }
    public String getDisplayName() { return displayName; }
    public Long getTenantId() { return tenantId; }
    public String getTenantCode() { return tenantCode; }
    public Set<TenantRole> getRoles() { return roles; }

    public boolean isTenantAdmin() {
        return roles.contains(TenantRole.TENANT_ADMIN);
    }
}
