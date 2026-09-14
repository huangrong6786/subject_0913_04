package com.evops.geothermal.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 单个井组单个时间桶的遥测聚合。 */
public class TelemetryBucketRow {
    private Long groupId;
    private String groupCode;
    private LocalDateTime bucketStart;
    private long sampleCount;
    private BigDecimal pressureMin;
    private BigDecimal pressureMax;
    private BigDecimal pressureAvg;
    private BigDecimal temperatureAvg;
    private BigDecimal flowAvg;

    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }
    public String getGroupCode() { return groupCode; }
    public void setGroupCode(String groupCode) { this.groupCode = groupCode; }
    public LocalDateTime getBucketStart() { return bucketStart; }
    public void setBucketStart(LocalDateTime bucketStart) { this.bucketStart = bucketStart; }
    public long getSampleCount() { return sampleCount; }
    public void setSampleCount(long sampleCount) { this.sampleCount = sampleCount; }
    public BigDecimal getPressureMin() { return pressureMin; }
    public void setPressureMin(BigDecimal pressureMin) { this.pressureMin = pressureMin; }
    public BigDecimal getPressureMax() { return pressureMax; }
    public void setPressureMax(BigDecimal pressureMax) { this.pressureMax = pressureMax; }
    public BigDecimal getPressureAvg() { return pressureAvg; }
    public void setPressureAvg(BigDecimal pressureAvg) { this.pressureAvg = pressureAvg; }
    public BigDecimal getTemperatureAvg() { return temperatureAvg; }
    public void setTemperatureAvg(BigDecimal temperatureAvg) { this.temperatureAvg = temperatureAvg; }
    public BigDecimal getFlowAvg() { return flowAvg; }
    public void setFlowAvg(BigDecimal flowAvg) { this.flowAvg = flowAvg; }
}
