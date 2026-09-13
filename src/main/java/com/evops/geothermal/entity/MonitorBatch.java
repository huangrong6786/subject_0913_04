package com.evops.geothermal.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.evops.common.BaseEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("t_monitor_batch")
public class MonitorBatch extends BaseEntity {
    private Long shiftId;
    private Long monitorPointId;
    private String batchNo;
    private LocalDate bizDate;
    private String wellheadSnapshot;
    private String status;
    private Integer acceptedVersion;
    private Long accountingId;
    private LocalDateTime acceptedAt;
    private Long acceptedBy;
    @Version
    private Integer version;

    public Long getShiftId() { return shiftId; }
    public void setShiftId(Long shiftId) { this.shiftId = shiftId; }
    public Long getMonitorPointId() { return monitorPointId; }
    public void setMonitorPointId(Long monitorPointId) { this.monitorPointId = monitorPointId; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public LocalDate getBizDate() { return bizDate; }
    public void setBizDate(LocalDate bizDate) { this.bizDate = bizDate; }
    public String getWellheadSnapshot() { return wellheadSnapshot; }
    public void setWellheadSnapshot(String wellheadSnapshot) { this.wellheadSnapshot = wellheadSnapshot; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getAcceptedVersion() { return acceptedVersion; }
    public void setAcceptedVersion(Integer acceptedVersion) { this.acceptedVersion = acceptedVersion; }
    public Long getAccountingId() { return accountingId; }
    public void setAccountingId(Long accountingId) { this.accountingId = accountingId; }
    public LocalDateTime getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(LocalDateTime acceptedAt) { this.acceptedAt = acceptedAt; }
    public Long getAcceptedBy() { return acceptedBy; }
    public void setAcceptedBy(Long acceptedBy) { this.acceptedBy = acceptedBy; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
