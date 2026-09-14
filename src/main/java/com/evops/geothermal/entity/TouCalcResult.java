package com.evops.geothermal.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 时序规则计算结果头：一次试验窗口 [windowStart, windowEnd)（井场时区，左闭右开）计算一行。
 * 唯一键 (tenant_id, target_type, target_id, window_start, window_end) 保证并发重算只有一份结果。
 */
@TableName("t_tou_calc_result")
public class TouCalcResult extends BaseEntity {
    private Long tenantId;
    private String calcNo;
    private String targetType;
    private Long targetId;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    private String windowTimezone;
    private Long ruleChainId;
    private Integer observationCount;
    private BigDecimal totalScaledInjectionM3;
    private Integer roundingScale;
    private String roundingMode;
    private String status;
    private String calcSnapshot;          // 逐版本冻结规则快照（区间/系数/阈值/生效窗口）

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getCalcNo() { return calcNo; }
    public void setCalcNo(String calcNo) { this.calcNo = calcNo; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public LocalDateTime getWindowStart() { return windowStart; }
    public void setWindowStart(LocalDateTime windowStart) { this.windowStart = windowStart; }
    public LocalDateTime getWindowEnd() { return windowEnd; }
    public void setWindowEnd(LocalDateTime windowEnd) { this.windowEnd = windowEnd; }
    public String getWindowTimezone() { return windowTimezone; }
    public void setWindowTimezone(String windowTimezone) { this.windowTimezone = windowTimezone; }
    public Long getRuleChainId() { return ruleChainId; }
    public void setRuleChainId(Long ruleChainId) { this.ruleChainId = ruleChainId; }
    public Integer getObservationCount() { return observationCount; }
    public void setObservationCount(Integer observationCount) { this.observationCount = observationCount; }
    public BigDecimal getTotalScaledInjectionM3() { return totalScaledInjectionM3; }
    public void setTotalScaledInjectionM3(BigDecimal totalScaledInjectionM3) { this.totalScaledInjectionM3 = totalScaledInjectionM3; }
    public Integer getRoundingScale() { return roundingScale; }
    public void setRoundingScale(Integer roundingScale) { this.roundingScale = roundingScale; }
    public String getRoundingMode() { return roundingMode; }
    public void setRoundingMode(String roundingMode) { this.roundingMode = roundingMode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCalcSnapshot() { return calcSnapshot; }
    public void setCalcSnapshot(String calcSnapshot) { this.calcSnapshot = calcSnapshot; }
}
