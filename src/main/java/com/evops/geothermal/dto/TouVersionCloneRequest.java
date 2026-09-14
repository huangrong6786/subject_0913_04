package com.evops.geothermal.dto;

import javax.validation.constraints.DecimalMin;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 基于历史版本开新草稿（“不能原地修改”的迭代入口）：
 * 区间集合从源版本复制，生效起点必须显式给出；压力阈值可覆盖。
 */
public class TouVersionCloneRequest {
    private LocalDateTime effectiveFrom;
    @DecimalMin("0.000")
    private BigDecimal pressureThresholdMpa;
    private String remark;

    public LocalDateTime getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDateTime effectiveFrom) { this.effectiveFrom = effectiveFrom; }
    public BigDecimal getPressureThresholdMpa() { return pressureThresholdMpa; }
    public void setPressureThresholdMpa(BigDecimal pressureThresholdMpa) { this.pressureThresholdMpa = pressureThresholdMpa; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
