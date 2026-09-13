package com.evops.geothermal.dto;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public class ShiftRequest {
    @NotNull
    private Long testSectionId;
    @NotNull
    private LocalDate shiftDate;              // 业务日期
    @NotNull
    @Min(1)
    @Max(99)
    private Integer shiftIndex;
    @Size(max = 64)
    private String operatorName;
    private BigDecimal plannedInjectionM3h;

    public Long getTestSectionId() { return testSectionId; }
    public void setTestSectionId(Long testSectionId) { this.testSectionId = testSectionId; }
    public LocalDate getShiftDate() { return shiftDate; }
    public void setShiftDate(LocalDate shiftDate) { this.shiftDate = shiftDate; }
    public Integer getShiftIndex() { return shiftIndex; }
    public void setShiftIndex(Integer shiftIndex) { this.shiftIndex = shiftIndex; }
    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }
    public BigDecimal getPlannedInjectionM3h() { return plannedInjectionM3h; }
    public void setPlannedInjectionM3h(BigDecimal plannedInjectionM3h) { this.plannedInjectionM3h = plannedInjectionM3h; }
}
