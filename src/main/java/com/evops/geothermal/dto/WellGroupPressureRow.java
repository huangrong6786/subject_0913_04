package com.evops.geothermal.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 单井组压力汇总行（40 井组压测）。
 * 全部井组由一条 GROUP BY SQL 产出，应用层不逐井发起查询（无 N+1）。
 */
public class WellGroupPressureRow {
    private Long groupId;
    private String groupCode;
    private String groupName;
    private long sampleCount;
    private BigDecimal pressureMin;
    private BigDecimal pressureMax;
    private BigDecimal pressureAvg;
    private BigDecimal latestPressure;
    private LocalDateTime latestReadingTime;
    private LocalDate windowFrom;
    private LocalDate windowTo;

    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }
    public String getGroupCode() { return groupCode; }
    public void setGroupCode(String groupCode) { this.groupCode = groupCode; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public long getSampleCount() { return sampleCount; }
    public void setSampleCount(long sampleCount) { this.sampleCount = sampleCount; }
    public BigDecimal getPressureMin() { return pressureMin; }
    public void setPressureMin(BigDecimal pressureMin) { this.pressureMin = pressureMin; }
    public BigDecimal getPressureMax() { return pressureMax; }
    public void setPressureMax(BigDecimal pressureMax) { this.pressureMax = pressureMax; }
    public BigDecimal getPressureAvg() { return pressureAvg; }
    public void setPressureAvg(BigDecimal pressureAvg) { this.pressureAvg = pressureAvg; }
    public BigDecimal getLatestPressure() { return latestPressure; }
    public void setLatestPressure(BigDecimal latestPressure) { this.latestPressure = latestPressure; }
    public LocalDateTime getLatestReadingTime() { return latestReadingTime; }
    public void setLatestReadingTime(LocalDateTime latestReadingTime) { this.latestReadingTime = latestReadingTime; }
    public LocalDate getWindowFrom() { return windowFrom; }
    public void setWindowFrom(LocalDate windowFrom) { this.windowFrom = windowFrom; }
    public LocalDate getWindowTo() { return windowTo; }
    public void setWindowTo(LocalDate windowTo) { this.windowTo = windowTo; }
}
