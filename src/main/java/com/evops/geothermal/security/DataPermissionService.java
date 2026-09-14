package com.evops.geothermal.security;

import org.springframework.stereotype.Service;

/**
 * 数据权限解析：每个查询入口都据此构造 {@link DataScope}，
 * 把“租户隔离 + 角色对象授权”作为强制谓词下推到 SQL，杜绝越权与全租户扫描。
 */
@Service
public class DataPermissionService {

    private final CurrentAccount currentAccount;

    public DataPermissionService(CurrentAccount currentAccount) {
        this.currentAccount = currentAccount;
    }

    public DataScope currentScope() {
        AccountPrincipal p = currentAccount.get();
        // 非租户管理员（TENANT_VIEWER）只能访问被显式授权的对象
        boolean restricted = !p.isTenantAdmin();
        return new DataScope(p.getTenantId(), p.getAccount(), restricted);
    }
}
