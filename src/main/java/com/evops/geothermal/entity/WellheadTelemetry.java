package com.evops.geothermal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 秒级井口遥测（高频事件流，独立于业务读数 t_wellhead_reading）。
 * partitionDate 是 reading_time 派生的日期分区裁剪键（DB 生成列），只读。
 */
@TableName("t_wellhead_telemetry")
public class WellheadTelemetry {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long wellGroupId;
    private Long testSectionId;
    private Long monitorPointId;
    private LocalDateTime readingTime;
    @TableField(insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER,
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private LocalDate partitionDate;
    private BigDecimal pressureMpa;
    private BigDecimal temperatureC;
    private BigDecimal flowM3h;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getWellGroupId() { return wellGroupId; }
    public void setWellGroupId(Long wellGroupId) { this.wellGroupId = wellGroupId; }
    public Long getTestSectionId() { return testSectionId; }
    public void setTestSectionId(Long testSectionId) { this.testSectionId = testSectionId; }
    public Long getMonitorPointId() { return monitorPointId; }
    public void setMonitorPointId(Long monitorPointId) { this.monitorPointId = monitorPointId; }
    public LocalDateTime getReadingTime() { return readingTime; }
    public void setReadingTime(LocalDateTime readingTime) { this.readingTime = readingTime; }
    public LocalDate getPartitionDate() { return partitionDate; }
    public void setPartitionDate(LocalDate partitionDate) { this.partitionDate = partitionDate; }
    public BigDecimal getPressureMpa() { return pressureMpa; }
    public void setPressureMpa(BigDecimal pressureMpa) { this.pressureMpa = pressureMpa; }
    public BigDecimal getTemperatureC() { return temperatureC; }
    public void setTemperatureC(BigDecimal temperatureC) { this.temperatureC = temperatureC; }
    public BigDecimal getFlowM3h() { return flowM3h; }
    public void setFlowM3h(BigDecimal flowM3h) { this.flowM3h = flowM3h; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
