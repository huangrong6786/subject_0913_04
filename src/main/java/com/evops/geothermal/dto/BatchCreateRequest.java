package com.evops.geothermal.dto;

import javax.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * 批次建立请求：以 井组-试验段-回灌班次（+监测点+业务日期）组成复合键。
 * 可传 shiftId 直接指定班次，或传复合键分量由服务端解析/隐式建班。
 */
public class BatchCreateRequest {
    private Long shiftId;
    private Long monitorPointId;
    /** 以下为复合键分量（shiftId 为空时使用） */
    private String groupCode;
    private String sectionCode;
    @NotNull
    private LocalDate bizDate;
    private Integer shiftIndex;
    private String pointCode;

    public Long getShiftId() { return shiftId; }
    public void setShiftId(Long shiftId) { this.shiftId = shiftId; }
    public Long getMonitorPointId() { return monitorPointId; }
    public void setMonitorPointId(Long monitorPointId) { this.monitorPointId = monitorPointId; }
    public String getGroupCode() { return groupCode; }
    public void setGroupCode(String groupCode) { this.groupCode = groupCode; }
    public String getSectionCode() { return sectionCode; }
    public void setSectionCode(String sectionCode) { this.sectionCode = sectionCode; }
    public LocalDate getBizDate() { return bizDate; }
    public void setBizDate(LocalDate bizDate) { this.bizDate = bizDate; }
    public Integer getShiftIndex() { return shiftIndex; }
    public void setShiftIndex(Integer shiftIndex) { this.shiftIndex = shiftIndex; }
    public String getPointCode() { return pointCode; }
    public void setPointCode(String pointCode) { this.pointCode = pointCode; }
}
