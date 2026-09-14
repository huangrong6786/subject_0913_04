package com.evops.geothermal.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 运营检索结果行：一条主记录（监测批次），关联维度以多对一标量字段平铺，
 * 不含一对多读数集合，保证主表不被放大。
 */
public class OperationBatchRow {
    private Long id;
    private String batchNo;
    private LocalDate bizDate;
    private String status;
    private Long groupId;
    private String groupCode;
    private String groupName;
    private Long sectionId;
    private String sectionCode;
    private Long shiftId;
    private String shiftCode;
    private Long pointId;
    private String pointCode;
    private String wellName;
    private BigDecimal batchPressureMin;
    private BigDecimal batchPressureMax;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public LocalDate getBizDate() { return bizDate; }
    public void setBizDate(LocalDate bizDate) { this.bizDate = bizDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
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
    public Long getShiftId() { return shiftId; }
    public void setShiftId(Long shiftId) { this.shiftId = shiftId; }
    public String getShiftCode() { return shiftCode; }
    public void setShiftCode(String shiftCode) { this.shiftCode = shiftCode; }
    public Long getPointId() { return pointId; }
    public void setPointId(Long pointId) { this.pointId = pointId; }
    public String getPointCode() { return pointCode; }
    public void setPointCode(String pointCode) { this.pointCode = pointCode; }
    public String getWellName() { return wellName; }
    public void setWellName(String wellName) { this.wellName = wellName; }
    public BigDecimal getBatchPressureMin() { return batchPressureMin; }
    public void setBatchPressureMin(BigDecimal batchPressureMin) { this.batchPressureMin = batchPressureMin; }
    public BigDecimal getBatchPressureMax() { return batchPressureMax; }
    public void setBatchPressureMax(BigDecimal batchPressureMax) { this.batchPressureMax = batchPressureMax; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
