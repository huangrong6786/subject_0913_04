package com.evops.geothermal.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 批次复合键关联视图：井组 + 试验段 + 班次 + 监测点 + 批次 + 读数 */
public class BatchDetailView {
    private Long batchId;
    private String batchNo;
    private String status;
    private LocalDate bizDate;
    private Integer acceptedVersion;
    private Long accountingId;
    private LocalDateTime acceptedAt;
    private String wellheadSnapshot;
    private Integer batchVersion;

    private Long groupId;
    private String groupCode;
    private String groupName;

    private Long sectionId;
    private String sectionCode;
    private String sectionName;

    private Long shiftId;
    private String shiftCode;
    private Integer shiftIndex;
    private String shiftStatus;

    private Long pointId;
    private String pointCode;
    private String pointName;
    private String wellName;

    private List<ReadingView> readings;

    public static class ReadingView {
        private Long id;
        private LocalDateTime readingTimeLocal;
        private BigDecimal pressureMpa;
        private BigDecimal temperatureC;
        private BigDecimal flowM3h;
        private BigDecimal injectionVolumeM3;
        private String wellheadSnapshot;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
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
    }

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDate getBizDate() { return bizDate; }
    public void setBizDate(LocalDate bizDate) { this.bizDate = bizDate; }
    public Integer getAcceptedVersion() { return acceptedVersion; }
    public void setAcceptedVersion(Integer acceptedVersion) { this.acceptedVersion = acceptedVersion; }
    public Long getAccountingId() { return accountingId; }
    public void setAccountingId(Long accountingId) { this.accountingId = accountingId; }
    public LocalDateTime getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(LocalDateTime acceptedAt) { this.acceptedAt = acceptedAt; }
    public String getWellheadSnapshot() { return wellheadSnapshot; }
    public void setWellheadSnapshot(String wellheadSnapshot) { this.wellheadSnapshot = wellheadSnapshot; }
    public Integer getBatchVersion() { return batchVersion; }
    public void setBatchVersion(Integer batchVersion) { this.batchVersion = batchVersion; }
    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }
    public String getGroupCode() { return groupCode; }
    public void setGroupCode(String groupCode) { this.groupCode = groupCode; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public Long getSectionId() { return sectionId; }
    public void setSectionId(Long sectionId) { this.sectionId = sectionId; }
    public String getSectionCode() { return sectionCode; }
    public void setSectionCode(String sectionCode) { this.sectionCode = sectionCode; }
    public String getSectionName() { return sectionName; }
    public void setSectionName(String sectionName) { this.sectionName = sectionName; }
    public Long getShiftId() { return shiftId; }
    public void setShiftId(Long shiftId) { this.shiftId = shiftId; }
    public String getShiftCode() { return shiftCode; }
    public void setShiftCode(String shiftCode) { this.shiftCode = shiftCode; }
    public Integer getShiftIndex() { return shiftIndex; }
    public void setShiftIndex(Integer shiftIndex) { this.shiftIndex = shiftIndex; }
    public String getShiftStatus() { return shiftStatus; }
    public void setShiftStatus(String shiftStatus) { this.shiftStatus = shiftStatus; }
    public Long getPointId() { return pointId; }
    public void setPointId(Long pointId) { this.pointId = pointId; }
    public String getPointCode() { return pointCode; }
    public void setPointCode(String pointCode) { this.pointCode = pointCode; }
    public String getPointName() { return pointName; }
    public void setPointName(String pointName) { this.pointName = pointName; }
    public String getWellName() { return wellName; }
    public void setWellName(String wellName) { this.wellName = wellName; }
    public List<ReadingView> getReadings() { return readings; }
    public void setReadings(List<ReadingView> readings) { this.readings = readings; }
}
