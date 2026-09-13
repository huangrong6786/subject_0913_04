package com.evops.geothermal.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.math.BigDecimal;

public class TestSectionRequest {
    @NotNull
    private Long wellGroupId;
    @NotBlank
    @Size(max = 32)
    private String sectionCode;
    @NotBlank
    @Size(max = 64)
    private String sectionName;
    private BigDecimal intervalTopM;
    private BigDecimal intervalBottomM;

    public Long getWellGroupId() { return wellGroupId; }
    public void setWellGroupId(Long wellGroupId) { this.wellGroupId = wellGroupId; }
    public String getSectionCode() { return sectionCode; }
    public void setSectionCode(String sectionCode) { this.sectionCode = sectionCode; }
    public String getSectionName() { return sectionName; }
    public void setSectionName(String sectionName) { this.sectionName = sectionName; }
    public BigDecimal getIntervalTopM() { return intervalTopM; }
    public void setIntervalTopM(BigDecimal intervalTopM) { this.intervalTopM = intervalTopM; }
    public BigDecimal getIntervalBottomM() { return intervalBottomM; }
    public void setIntervalBottomM(BigDecimal intervalBottomM) { this.intervalBottomM = intervalBottomM; }
}
