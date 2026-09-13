package com.evops.geothermal.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("t_wellhead_reading")
public class WellheadReading extends BaseEntity {
    private Long batchId;
    private LocalDateTime readingTimeLocal;
    private BigDecimal pressureMpa;
    private BigDecimal temperatureC;
    private BigDecimal flowM3h;
    private BigDecimal injectionVolumeM3;
    private String wellheadSnapshot;
    @Version
    private Integer version;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public LocalDateTime getReadingTimeLocal() { return readingTimeLocal; }
    public void setReadingTimeLocal(LocalDateTime readingTimeLocal) { this.readingTimeLocal = readingTimeLocal; }
    public BigDecimal getPressureMpa() { return pressureMpa; }
    public void setPressureMpa(BigDecimal pressureMpa) { this.pressureMpa = pressureMpa; }
    public BigDecimal getTemperatureC() { return temperatureC; }
    public void setTemperatureC(BigDecimal temperatureC) { this.temperatureC = temperatureC; }
    public BigDecimal getFlowM3h() { return flowM3h; }
    public void setFlowM3h(BigDecimal flowM3h) { this.flowM3h = flowM3h; }
    public BigDecimal getInjectionVolumeM3() { return injectionVolumeM3; }
    public void setInjectionVolumeM3(BigDecimal injectionVolumeM3) { this.injectionVolumeM3 = injectionVolumeM3; }
    public String getWellheadSnapshot() { return wellheadSnapshot; }
    public void setWellheadSnapshot(String wellheadSnapshot) { this.wellheadSnapshot = wellheadSnapshot; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
