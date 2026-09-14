package com.evops.geothermal.dto;

import com.evops.geothermal.security.DataScope;

import java.time.LocalDate;
import java.util.List;

/** 40 井组压力汇总 SQL 入参（一条 GROUP BY 覆盖全部授权井组）。 */
public class WellGroupPressureParams {
    private long tenantId;
    private boolean restricted;
    private String account;
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private List<Long> groupIds;

    public static WellGroupPressureParams of(DataScope scope, LocalDate from, LocalDate to, List<Long> groupIds) {
        WellGroupPressureParams p = new WellGroupPressureParams();
        p.tenantId = scope.getTenantId();
        p.restricted = scope.isRestricted();
        p.account = scope.getAccount();
        p.dateFrom = from;
        p.dateTo = to;
        p.groupIds = groupIds;
        return p;
    }

    public long getTenantId() { return tenantId; }
    public void setTenantId(long tenantId) { this.tenantId = tenantId; }
    public boolean isRestricted() { return restricted; }
    public void setRestricted(boolean restricted) { this.restricted = restricted; }
    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }
    public LocalDate getDateFrom() { return dateFrom; }
    public void setDateFrom(LocalDate dateFrom) { this.dateFrom = dateFrom; }
    public LocalDate getDateTo() { return dateTo; }
    public void setDateTo(LocalDate dateTo) { this.dateTo = dateTo; }
    public List<Long> getGroupIds() { return groupIds; }
    public void setGroupIds(List<Long> groupIds) { this.groupIds = groupIds; }
}
