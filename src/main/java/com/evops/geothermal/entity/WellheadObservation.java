package com.evops.geothermal.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 井口观测数据（CSV 批量导入落库目标表）。
 * 业务键：(tenant_id, monitor_point_id, serial_no) 与 (tenant_id, monitor_point_id, observed_at)，
 * 网关重发同序列号 / 跨天补报重复到达时按业务键幂等更新，不产生重复记录。
 */
@TableName("t_wellhead_observation")
public class WellheadObservation extends BaseEntity {
    private Long tenantId;
    private Long monitorPointId;
    private String objectCode;
    private String serialNo;
    private String sourceDevice;
    private LocalDateTime observedAt;
    private LocalDate bizDate;
    private BigDecimal pressureMpa;
    private BigDecimal temperatureC;
    private BigDecimal flowM3h;
    private BigDecimal injectionVolumeM3;
    private Long importFileId;
    private Integer rowNo;
    @Version
    private Integer version;

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getMonitorPointId() { return monitorPointId; }
    public void setMonitorPointId(Long monitorPointId) { this.monitorPointId = monitorPointId; }
    public String getObjectCode() { return objectCode; }
    public void setObjectCode(String objectCode) { this.objectCode = objectCode; }
    public String getSerialNo() { return serialNo; }
    public void setSerialNo(String serialNo) { this.serialNo = serialNo; }
    public String getSourceDevice() { return sourceDevice; }
    public void setSourceDevice(String sourceDevice) { this.sourceDevice = sourceDevice; }
    public LocalDateTime getObservedAt() { return observedAt; }
    public void setObservedAt(LocalDateTime observedAt) { this.observedAt = observedAt; }
    public LocalDate getBizDate() { return bizDate; }
    public void setBizDate(LocalDate bizDate) { this.bizDate = bizDate; }
    public BigDecimal getPressureMpa() { return pressureMpa; }
    public void setPressureMpa(BigDecimal pressureMpa) { this.pressureMpa = pressureMpa; }
    public BigDecimal getTemperatureC() { return temperatureC; }
    public void setTemperatureC(BigDecimal temperatureC) { this.temperatureC = temperatureC; }
    public BigDecimal getFlowM3h() { return flowM3h; }
    public void setFlowM3h(BigDecimal flowM3h) { this.flowM3h = flowM3h; }
    public BigDecimal getInjectionVolumeM3() { return injectionVolumeM3; }
    public void setInjectionVolumeM3(BigDecimal injectionVolumeM3) { this.injectionVolumeM3 = injectionVolumeM3; }
    public Long getImportFileId() { return importFileId; }
    public void setImportFileId(Long importFileId) { this.importFileId = importFileId; }
    public Integer getRowNo() { return rowNo; }
    public void setRowNo(Integer rowNo) { this.rowNo = rowNo; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
