package com.evops.geothermal.dto;

import com.evops.geothermal.security.DataScope;

import java.time.LocalDate;

/** 遥测时间桶聚合 SQL 入参（含强制数据权限与分区裁剪范围）。 */
public class TelemetryAggParams {
    private long tenantId;
    private boolean restricted;
    private String account;
    private Long groupId;
    private Long monitorPointId;
    private LocalDate partitionFrom;
    private LocalDate partitionTo;
    private int bucketSeconds;

    public static TelemetryAggParams of(TelemetryAggregateQuery q, DataScope scope,
                                        LocalDate partitionFrom, LocalDate partitionTo, int bucketSeconds) {
        TelemetryAggParams p = new TelemetryAggParams();
        p.tenantId = scope.getTenantId();
        p.restricted = scope.isRestricted();
        p.account = scope.getAccount();
        p.groupId = q.getGroupId();
        p.monitorPointId = q.getMonitorPointId();
        p.partitionFrom = partitionFrom;
        p.partitionTo = partitionTo;
        p.bucketSeconds = bucketSeconds;
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
    public Long getMonitorPointId() { return monitorPointId; }
    public void setMonitorPointId(Long monitorPointId) { this.monitorPointId = monitorPointId; }
    public LocalDate getPartitionFrom() { return partitionFrom; }
    public void setPartitionFrom(LocalDate partitionFrom) { this.partitionFrom = partitionFrom; }
    public LocalDate getPartitionTo() { return partitionTo; }
    public void setPartitionTo(LocalDate partitionTo) { this.partitionTo = partitionTo; }
    public int getBucketSeconds() { return bucketSeconds; }
    public void setBucketSeconds(int bucketSeconds) { this.bucketSeconds = bucketSeconds; }
}
