package com.evops.geothermal.security;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Component;

/**
 * 把数据权限（租户 + 角色对象授权）下推到 QueryWrapper。
 * 列名均为数据库下划线列；授权单元为井组（WELL_GROUP），下级对象沿所属井组收敛。
 */
@Component
public class QueryScopeApplier {

    private static final String GRANTED_GROUP_SQL =
            "SELECT object_id FROM t_object_grant WHERE tenant_id = %d AND grantee_account = '%s' "
                    + "AND object_type = 'WELL_GROUP'";

    /** 主表即井组表：按 t_well_group 主键列 id 收敛。 */
    public <T> void scopeWellGroupTable(QueryWrapper<T> qw, DataScope scope) {
        qw.eq("tenant_id", scope.getTenantId());
        if (scope.isRestricted()) {
            qw.inSql("id", grantedGroups(scope));
        }
    }

    /** 主表持有 well_group_id 列（如 t_test_section、t_monitor_batch 冗余列）。 */
    public <T> void scopeByGroupColumn(QueryWrapper<T> qw, DataScope scope, String groupColumn) {
        qw.eq("tenant_id", scope.getTenantId());
        if (scope.isRestricted()) {
            qw.inSql(groupColumn, grantedGroups(scope));
        }
    }

    /** 主表通过 section 间接归属井组（如 t_monitor_point / t_reinjection_shift）。 */
    public <T> void scopeBySectionColumn(QueryWrapper<T> qw, DataScope scope, String sectionColumn) {
        qw.eq("tenant_id", scope.getTenantId());
        if (scope.isRestricted()) {
            qw.inSql(sectionColumn,
                    "SELECT s.id FROM t_test_section s WHERE s.well_group_id IN (" + grantedGroups(scope) + ")");
        }
    }

    private String grantedGroups(DataScope scope) {
        // 账号在认证目录受控（无单引号），此处仍做防御性转义
        String safeAccount = scope.getAccount().replace("'", "''");
        return String.format(GRANTED_GROUP_SQL, scope.getTenantId(), safeAccount);
    }
}
