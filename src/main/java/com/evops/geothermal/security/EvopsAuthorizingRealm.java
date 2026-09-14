package com.evops.geothermal.security;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 内置账号目录 + Shiro 域。
 *
 * 账号（HTTP Basic）：
 *  - bootstrap / bootstrap：租户 1 管理员（向后兼容既有交付）
 *  - t1admin   / t1admin  ：租户 1 管理员，可见租户 1 全部对象
 *  - t1view    / t1view   ：租户 1 只读账号，仅见 t_object_grant 授权对象
 *  - t2admin   / t2admin  ：租户 2 管理员，可见租户 2 全部对象
 */
public class EvopsAuthorizingRealm extends AuthorizingRealm {

    static class UserDef {
        final String password;
        final String displayName;
        final long tenantId;
        final String tenantCode;
        final Set<TenantRole> roles;

        UserDef(String password, String displayName, long tenantId, String tenantCode, TenantRole... roles) {
            this.password = password;
            this.displayName = displayName;
            this.tenantId = tenantId;
            this.tenantCode = tenantCode;
            EnumSet<TenantRole> set = EnumSet.noneOf(TenantRole.class);
            Collections.addAll(set, roles);
            this.roles = Collections.unmodifiableSet(set);
        }
    }

    private final Map<String, UserDef> users = new LinkedHashMap<>();

    public EvopsAuthorizingRealm() {
        put("bootstrap", new UserDef("bootstrap", "内置管理员", 1L, "T1", TenantRole.TENANT_ADMIN));
        put("t1admin", new UserDef("t1admin", "关中公司管理员", 1L, "T1", TenantRole.TENANT_ADMIN));
        put("t1view", new UserDef("t1view", "关中公司受限查看员", 1L, "T1", TenantRole.TENANT_VIEWER));
        put("t2admin", new UserDef("t2admin", "河西公司管理员", 2L, "T2", TenantRole.TENANT_ADMIN));
    }

    private void put(String account, UserDef def) {
        users.put(account, def);
    }

    @Override
    public boolean supports(AuthenticationToken token) {
        return token instanceof UsernamePasswordToken;
    }

    @Override
    protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
        AccountPrincipal principal = (AccountPrincipal) principals.getPrimaryPrincipal();
        SimpleAuthorizationInfo info = new SimpleAuthorizationInfo();
        for (TenantRole role : principal.getRoles()) {
            info.addRole(role.name());
        }
        return info;
    }

    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
        String username = (String) token.getPrincipal();
        UserDef def = users.get(username);
        if (def == null) {
            return null; // Shiro 转为 UnknownAccountException，认证失败
        }
        AccountPrincipal principal = new AccountPrincipal(
                username, def.displayName, def.tenantId, def.tenantCode, def.roles);
        return new SimpleAuthenticationInfo(principal, def.password, getName());
    }
}
