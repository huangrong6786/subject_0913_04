package com.evops.geothermal.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 时序规则版本：峰/平/谷区间集合 + 井口压力阈值 + 生效窗口 [effectiveFrom, effectiveTo)。
 * 启用（ENABLED）后冻结：{@link #frozenSnapshot} 与区间行均不可原地修改，只能新建下一版本。
 */
@TableName("t_tou_rule_set")
public class TouRuleSet extends BaseEntity {
    private Long tenantId;
    private Long chainId;
    private Integer versionNo;
    private String status;                // TouRuleStatus: DRAFT/ENABLED/SUPERSEDED
    private LocalDateTime effectiveFrom;  // 生效起点（含）
    private LocalDateTime effectiveTo;    // 生效终点（不含）；null 为开放区间
    private BigDecimal pressureThresholdMpa;
    private String frozenSnapshot;        // 启用时冻结的规则要素 JSON
    private String remark;
    private LocalDateTime freezeTime;
    private Long freezeBy;
    @Version
    private Integer version;

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getChainId() { return chainId; }
    public void setChainId(Long chainId) { this.chainId = chainId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDateTime effectiveFrom) { this.effectiveFrom = effectiveFrom; }
    public LocalDateTime getEffectiveTo() { return effectiveTo; }
    public void setEffectiveTo(LocalDateTime effectiveTo) { this.effectiveTo = effectiveTo; }
    public BigDecimal getPressureThresholdMpa() { return pressureThresholdMpa; }
    public void setPressureThresholdMpa(BigDecimal pressureThresholdMpa) { this.pressureThresholdMpa = pressureThresholdMpa; }
    public String getFrozenSnapshot() { return frozenSnapshot; }
    public void setFrozenSnapshot(String frozenSnapshot) { this.frozenSnapshot = frozenSnapshot; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public LocalDateTime getFreezeTime() { return freezeTime; }
    public void setFreezeTime(LocalDateTime freezeTime) { this.freezeTime = freezeTime; }
    public Long getFreezeBy() { return freezeBy; }
    public void setFreezeBy(Long freezeBy) { this.freezeBy = freezeBy; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
