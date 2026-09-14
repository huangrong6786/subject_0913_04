package com.evops.geothermal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 对象级数据授权：TENANT_VIEWER 角色账号只能访问被显式授权的对象；
 * TENANT_ADMIN 不依赖此表（可见本租户全部对象）。
 */
@TableName("t_object_grant")
public class ObjectGrant {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String granteeAccount;
    private String objectType;
    private Long objectId;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getGranteeAccount() { return granteeAccount; }
    public void setGranteeAccount(String granteeAccount) { this.granteeAccount = granteeAccount; }
    public String getObjectType() { return objectType; }
    public void setObjectType(String objectType) { this.objectType = objectType; }
    public Long getObjectId() { return objectId; }
    public void setObjectId(Long objectId) { this.objectId = objectId; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
