package com.evops.geothermal.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;

@TableName("t_reinjection_shift")
public class ReinjectionShift extends BaseEntity {
    private Long tenantId;
    private Long testSectionId;
    private LocalDate shiftDate;
    private Integer shiftIndex;
    private String shiftCode;
    private String operatorName;
    private BigDecimal plannedInjectionM3h;
    private String status;
    @Version
    private Integer version;

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getTestSectionId() { return testSectionId; }
    public void setTestSectionId(Long testSectionId) { this.testSectionId = testSectionId; }
    public LocalDate getShiftDate() { return shiftDate; }
    public void setShiftDate(LocalDate shiftDate) { this.shiftDate = shiftDate; }
    public Integer getShiftIndex() { return shiftIndex; }
    public void setShiftIndex(Integer shiftIndex) { this.shiftIndex = shiftIndex; }
    public String getShiftCode() { return shiftCode; }
    public void setShiftCode(String shiftCode) { this.shiftCode = shiftCode; }
    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }
    public BigDecimal getPlannedInjectionM3h() { return plannedInjectionM3h; }
    public void setPlannedInjectionM3h(BigDecimal plannedInjectionM3h) { this.plannedInjectionM3h = plannedInjectionM3h; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
