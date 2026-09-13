package com.evops.geothermal.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

public class MonitorPointRequest {
    @NotNull
    private Long testSectionId;
    @NotBlank
    @Size(max = 32)
    private String pointCode;
    @NotBlank
    @Size(max = 64)
    private String pointName;
    @NotBlank
    @Size(max = 64)
    private String wellName;
    @Size(max = 16)
    private String pointType;

    public Long getTestSectionId() { return testSectionId; }
    public void setTestSectionId(Long testSectionId) { this.testSectionId = testSectionId; }
    public String getPointCode() { return pointCode; }
    public void setPointCode(String pointCode) { this.pointCode = pointCode; }
    public String getPointName() { return pointName; }
    public void setPointName(String pointName) { this.pointName = pointName; }
    public String getWellName() { return wellName; }
    public void setWellName(String wellName) { this.wellName = wellName; }
    public String getPointType() { return pointType; }
    public void setPointType(String pointType) { this.pointType = pointType; }
}
