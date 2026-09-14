package com.evops.geothermal.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.evops.common.BaseEntity;

/**
 * 时序规则链：一个绑定对象（监测点 / 井组）一条链，链上承载多个不可变版本。
 */
@TableName("t_tou_rule_chain")
public class TouRuleChain extends BaseEntity {
    private Long tenantId;
    private String chainCode;
    private String chainName;
    private String targetType;            // TouTargetType: MONITOR_POINT / WELL_GROUP
    private Long targetId;
    private Long currentVersionId;        // 当前生效版本 id；null 表示尚无启用版本
    private String status;
    @Version
    private Integer version;

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getChainCode() { return chainCode; }
    public void setChainCode(String chainCode) { this.chainCode = chainCode; }
    public String getChainName() { return chainName; }
    public void setChainName(String chainName) { this.chainName = chainName; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public Long getCurrentVersionId() { return currentVersionId; }
    public void setCurrentVersionId(Long currentVersionId) { this.currentVersionId = currentVersionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
