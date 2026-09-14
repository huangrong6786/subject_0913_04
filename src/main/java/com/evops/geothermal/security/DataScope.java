package com.evops.geothermal.security;

/**
 * 一次查询的数据权限范围（不可变）。
 * tenantId：租户隔离谓词，每次查询必带；
 * restricted=true 时（TENANT_VIEWER）还要叠加对象级授权收敛（t_object_grant），
 * TENANT_ADMIN 只见本租户全部对象、不依赖授权表。
 */
public class DataScope {
    private final long tenantId;
    private final String account;
    private final boolean restricted;

    public DataScope(long tenantId, String account, boolean restricted) {
        this.tenantId = tenantId;
        this.account = account;
        this.restricted = restricted;
    }

    public long getTenantId() { return tenantId; }
    public String getAccount() { return account; }
    public boolean isRestricted() { return restricted; }
}
