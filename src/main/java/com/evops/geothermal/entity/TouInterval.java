package com.evops.geothermal.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;

/**
 * 规则区间：一天 1440 分钟圆环上的左闭右开区间 [startMinute, endMinute)。
 * endMinute &lt;= startMinute 表示跨午夜（如谷段 22:00-次日 06:00）。
 * 仅所属版本为 DRAFT 时可写；版本启用后该行随版本永久冻结。
 */
@TableName("t_tou_interval")
public class TouInterval extends BaseEntity {
    private Long tenantId;
    private Long ruleSetId;
    private String intervalCode;
    private String segmentType;           // SegmentType: PEAK / FLAT / VALLEY
    private Integer startMinute;
    private Integer endMinute;
    private BigDecimal priceCoefficient;
    private Integer seqNo;

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getRuleSetId() { return ruleSetId; }
    public void setRuleSetId(Long ruleSetId) { this.ruleSetId = ruleSetId; }
    public String getIntervalCode() { return intervalCode; }
    public void setIntervalCode(String intervalCode) { this.intervalCode = intervalCode; }
    public String getSegmentType() { return segmentType; }
    public void setSegmentType(String segmentType) { this.segmentType = segmentType; }
    public Integer getStartMinute() { return startMinute; }
    public void setStartMinute(Integer startMinute) { this.startMinute = startMinute; }
    public Integer getEndMinute() { return endMinute; }
    public void setEndMinute(Integer endMinute) { this.endMinute = endMinute; }
    public BigDecimal getPriceCoefficient() { return priceCoefficient; }
    public void setPriceCoefficient(BigDecimal priceCoefficient) { this.priceCoefficient = priceCoefficient; }
    public Integer getSeqNo() { return seqNo; }
    public void setSeqNo(Integer seqNo) { this.seqNo = seqNo; }
}
