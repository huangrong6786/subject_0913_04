package com.evops.geothermal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 计算明细：试验窗口内每条观测归入的规则版本、区间实例与时段计量。
 * 明细永久保留 {@link #ruleSetId}/{@link #ruleVersionNo} 与当时的系数/阈值快照，
 * 规则后续迭代不影响历史明细（历史结果读取当时快照）。
 */
@TableName("t_tou_calc_detail")
public class TouCalcDetail {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long calcResultId;
    private Long observationId;
    private LocalDateTime observedAt;
    private LocalDate bizDate;
    private String segmentType;
    private String intervalCode;
    private Integer startMinute;
    private Integer endMinute;
    private Integer dayOffset;              // 跨午夜区间实例相对业务日期的偏移
    private LocalDateTime segmentStart;
    private LocalDateTime segmentEnd;       // 不含
    private Long ruleSetId;
    private Integer ruleVersionNo;
    private BigDecimal rawInjectionVolumeM3;
    private BigDecimal priceCoefficient;
    private BigDecimal scaledInjectionM3;   // 精确乘积，不舍入
    private BigDecimal pressureMpa;
    private BigDecimal pressureThresholdMpa;
    private Boolean thresholdBreached;
    private String detailSnapshot;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCalcResultId() { return calcResultId; }
    public void setCalcResultId(Long calcResultId) { this.calcResultId = calcResultId; }
    public Long getObservationId() { return observationId; }
    public void setObservationId(Long observationId) { this.observationId = observationId; }
    public LocalDateTime getObservedAt() { return observedAt; }
    public void setObservedAt(LocalDateTime observedAt) { this.observedAt = observedAt; }
    public LocalDate getBizDate() { return bizDate; }
    public void setBizDate(LocalDate bizDate) { this.bizDate = bizDate; }
    public String getSegmentType() { return segmentType; }
    public void setSegmentType(String segmentType) { this.segmentType = segmentType; }
    public String getIntervalCode() { return intervalCode; }
    public void setIntervalCode(String intervalCode) { this.intervalCode = intervalCode; }
    public Integer getStartMinute() { return startMinute; }
    public void setStartMinute(Integer startMinute) { this.startMinute = startMinute; }
    public Integer getEndMinute() { return endMinute; }
    public void setEndMinute(Integer endMinute) { this.endMinute = endMinute; }
    public Integer getDayOffset() { return dayOffset; }
    public void setDayOffset(Integer dayOffset) { this.dayOffset = dayOffset; }
    public LocalDateTime getSegmentStart() { return segmentStart; }
    public void setSegmentStart(LocalDateTime segmentStart) { this.segmentStart = segmentStart; }
    public LocalDateTime getSegmentEnd() { return segmentEnd; }
    public void setSegmentEnd(LocalDateTime segmentEnd) { this.segmentEnd = segmentEnd; }
    public Long getRuleSetId() { return ruleSetId; }
    public void setRuleSetId(Long ruleSetId) { this.ruleSetId = ruleSetId; }
    public Integer getRuleVersionNo() { return ruleVersionNo; }
    public void setRuleVersionNo(Integer ruleVersionNo) { this.ruleVersionNo = ruleVersionNo; }
    public BigDecimal getRawInjectionVolumeM3() { return rawInjectionVolumeM3; }
    public void setRawInjectionVolumeM3(BigDecimal rawInjectionVolumeM3) { this.rawInjectionVolumeM3 = rawInjectionVolumeM3; }
    public BigDecimal getPriceCoefficient() { return priceCoefficient; }
    public void setPriceCoefficient(BigDecimal priceCoefficient) { this.priceCoefficient = priceCoefficient; }
    public BigDecimal getScaledInjectionM3() { return scaledInjectionM3; }
    public void setScaledInjectionM3(BigDecimal scaledInjectionM3) { this.scaledInjectionM3 = scaledInjectionM3; }
    public BigDecimal getPressureMpa() { return pressureMpa; }
    public void setPressureMpa(BigDecimal pressureMpa) { this.pressureMpa = pressureMpa; }
    public BigDecimal getPressureThresholdMpa() { return pressureThresholdMpa; }
    public void setPressureThresholdMpa(BigDecimal pressureThresholdMpa) { this.pressureThresholdMpa = pressureThresholdMpa; }
    public Boolean getThresholdBreached() { return thresholdBreached; }
    public void setThresholdBreached(Boolean thresholdBreached) { this.thresholdBreached = thresholdBreached; }
    public String getDetailSnapshot() { return detailSnapshot; }
    public void setDetailSnapshot(String detailSnapshot) { this.detailSnapshot = detailSnapshot; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
