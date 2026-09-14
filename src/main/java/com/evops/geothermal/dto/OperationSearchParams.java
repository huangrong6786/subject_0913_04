package com.evops.geothermal.dto;

import com.evops.geothermal.security.DataScope;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 运营检索 SQL 入参：业务过滤 + 数据权限范围 + 确定性分页量。
 * 由服务层在每次查询时填充，租户与授权谓词不可由调用方绕过。
 */
public class OperationSearchParams {
    // 数据权限（必带）
    private long tenantId;
    private boolean restricted;
    private String account;
    // 业务过滤
    private Long groupId;
    private Long sectionId;
    private String status;
    private LocalDate bizDateFrom;
    private LocalDate bizDateTo;
    private BigDecimal pressureMin;
    private BigDecimal pressureMax;
    // 分页（确定性排序键 biz_date DESC, id ASC）
    private LocalDate cursorBizDate;
    private Long cursorId;
    private int offset;
    private int limit;

    public static OperationSearchParams of(OperationBatchQuery q, DataScope scope) {
        OperationSearchParams p = new OperationSearchParams();
        p.tenantId = scope.getTenantId();
        p.restricted = scope.isRestricted();
        p.account = scope.getAccount();
        p.groupId = q.getGroupId();
        p.sectionId = q.getSectionId();
        p.status = q.getStatus();
        p.bizDateFrom = q.getBizDateFrom();
        p.bizDateTo = q.getBizDateTo();
        p.pressureMin = q.getPressureMin();
        p.pressureMax = q.getPressureMax();
        return p;
    }

    public long getTenantId() { return tenantId; }
    public void setTenantId(long tenantId) { this.tenantId = tenantId; }
    public boolean isRestricted() { return restricted; }
    public void setRestricted(boolean restricted) { this.restricted = restricted; }
    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }
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
    public LocalDate getCursorBizDate() { return cursorBizDate; }
    public void setCursorBizDate(LocalDate cursorBizDate) { this.cursorBizDate = cursorBizDate; }
    public Long getCursorId() { return cursorId; }
    public void setCursorId(Long cursorId) { this.cursorId = cursorId; }
    public int getOffset() { return offset; }
    public void setOffset(int offset) { this.offset = offset; }
    public int getLimit() { return limit; }
    public void setLimit(int limit) { this.limit = limit; }
}
