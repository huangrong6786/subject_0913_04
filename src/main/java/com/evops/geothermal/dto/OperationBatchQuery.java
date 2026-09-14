package com.evops.geothermal.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 运营检索条件（回灌试验/批次 + 井口压力组合）。
 * 至少支持 4 个条件的 AND/范围组合：井组、试验段、状态、业务日期区间、井口压力区间。
 * 分页：优先使用游标（cursor，上一页最后一条的 bizDate|id），也支持 1 起始的 page 偏移；
 * pageSize 限定 1-100。
 */
public class OperationBatchQuery {
    private Long groupId;
    private Long sectionId;
    private String status;
    private LocalDate bizDateFrom;
    private LocalDate bizDateTo;
    private BigDecimal pressureMin;   // 井口压力下限(MPa)
    private BigDecimal pressureMax;   // 井口压力上限(MPa)
    private Integer pageSize = 20;
    private Integer page = 1;
    private String cursor;

    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }
    public Long getSectionId() { return sectionId; }
    public void setSectionId(Long sectionId) { this.sectionId = sectionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDate getBizDateFrom() { return bizDateFrom; }
    public void setBizDateFrom(LocalDate bizDateFrom) { this.bizDateFrom = bizDateFrom; }
    public LocalDate getBizDateTo() { return bizDateTo; }
    public void setBizDateTo(LocalDate bizDateTo) { this.bizDateTo = bizDateTo; }
    public BigDecimal getPressureMin() { return pressureMin; }
    public void setPressureMin(BigDecimal pressureMin) { this.pressureMin = pressureMin; }
    public BigDecimal getPressureMax() { return pressureMax; }
    public void setPressureMax(BigDecimal pressureMax) { this.pressureMax = pressureMax; }
    public Integer getPageSize() { return pageSize; }
    public void setPageSize(Integer pageSize) { this.pageSize = pageSize; }
    public Integer getPage() { return page; }
    public void setPage(Integer page) { this.page = page; }
    public String getCursor() { return cursor; }
    public void setCursor(String cursor) { this.cursor = cursor; }
}
