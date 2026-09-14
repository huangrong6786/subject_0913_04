package com.evops.geothermal.dto;

import javax.validation.Valid;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 规则版本草稿请求：生效起点（含，对象时区）、井口压力阈值、峰平谷区间集合。
 * 区间在一天 1440 分钟圆环上左闭右开定义，允许跨午夜；启用时统一校验重叠/全覆盖。
 */
public class TouVersionDraftRequest {
    @NotNull
    private LocalDateTime effectiveFrom;
    @NotNull
    @DecimalMin("0.000")
    private BigDecimal pressureThresholdMpa;
    @Size(max = 255)
    private String remark;
    @NotEmpty
    @Valid
    private List<IntervalItem> intervals;

    public static class IntervalItem {
        @NotNull
        @Size(max = 32)
        private String intervalCode;
        /** PEAK / FLAT / VALLEY */
        @NotNull
        private String segmentType;
        @NotNull
        private Integer startMinute;
        @NotNull
        private Integer endMinute;
        @NotNull
        @DecimalMin("0.000000")
        private BigDecimal priceCoefficient;

        public String getIntervalCode() { return intervalCode; }
        public void setIntervalCode(String intervalCode) { this.intervalCode = intervalCode; }
        public String getSegmentType() { return segmentType; }
        public void setSegmentType(String segmentType) { this.segmentType = segmentType; }
        public Integer getStartMinute() { return startMinute; }
        public void setStartMinute(Integer startMinute) { this.startMinute = startMinute; }
        public Integer getEndMinute() { return endMinute; }
        public void setEndMinute(Integer endMinute) { this.endMinute = endMinute; }
        public BigDecimal getPriceCoefficient() { return priceCoefficient; }
        public void setPriceCoefficient(BigDecimal priceCoefficient) { this.priceCoefficient = priceCoefficient; }
    }

    public LocalDateTime getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDateTime effectiveFrom) { this.effectiveFrom = effectiveFrom; }
    public BigDecimal getPressureThresholdMpa() { return pressureThresholdMpa; }
    public void setPressureThresholdMpa(BigDecimal pressureThresholdMpa) { this.pressureThresholdMpa = pressureThresholdMpa; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public List<IntervalItem> getIntervals() { return intervals; }
    public void setIntervals(List<IntervalItem> intervals) { this.intervals = intervals; }
}
