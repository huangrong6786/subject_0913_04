package com.evops.geothermal.security;

/** 租户内角色：管理员可见本租户全部对象；只读账号仅可见被显式授权的对象。 */
public enum TenantRole {
    TENANT_ADMIN,
    TENANT_VIEWER
}
