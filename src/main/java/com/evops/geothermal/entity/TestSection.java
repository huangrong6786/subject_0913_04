package com.evops.geothermal.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;

@TableName("t_test_section")
public class TestSection extends BaseEntity {
    private Long wellGroupId;
    private String sectionCode;
    private String sectionName;
    private BigDecimal intervalTopM;
    private BigDecimal intervalBottomM;
    private String status;
    @Version
    private Integer version;

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
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
