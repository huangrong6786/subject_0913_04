package com.evops.geothermal.dto;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/** 草稿版本内单个区间的修改（仅 DRAFT 版本允许）。 */
public class TouIntervalUpdateRequest {
    @NotNull
    private String segmentType;
    @NotNull
    private Integer startMinute;
    @NotNull
    private Integer endMinute;
    @NotNull
    @DecimalMin("0.000000")
    private BigDecimal priceCoefficient;

    public String getSegmentType() { return segmentType; }
    public void setSegmentType(String segmentType) { this.segmentType = segmentType; }
    public Integer getStartMinute() { return startMinute; }
    public void setStartMinute(Integer startMinute) { this.startMinute = startMinute; }
    public Integer getEndMinute() { return endMinute; }
    public void setEndMinute(Integer endMinute) { this.endMinute = endMinute; }
    public BigDecimal getPriceCoefficient() { return priceCoefficient; }
    public void setPriceCoefficient(BigDecimal coefficient) { this.priceCoefficient = coefficient; }
}
