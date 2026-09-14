package com.evops.geothermal.dto;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 秒级遥测聚合查询。
 * dateFrom/dateTo 必填，服务层强制把它们下推为 partition_date 范围谓词（分区裁剪），
 * 并限制最大时间窗，杜绝全表扫描；粒度决定时间桶秒数。
 */
public class TelemetryAggregateQuery {
    private Long groupId;
    private Long monitorPointId;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateTo;
    private String granularity; // MINUTE/FIVE_MIN/TEN_MIN/HOUR/DAY，缺省 HOUR

    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }
    public Long getMonitorPointId() { return monitorPointId; }
    public void setMonitorPointId(Long monitorPointId) { this.monitorPointId = monitorPointId; }
    public LocalDate getDateFrom() { return dateFrom; }
    public void setDateFrom(LocalDate dateFrom) { this.dateFrom = dateFrom; }
    public LocalDate getDateTo() { return dateTo; }
    public void setDateTo(LocalDate dateTo) { this.dateTo = dateTo; }
    public String getGranularity() { return granularity; }
    public void setGranularity(String granularity) { this.granularity = granularity; }
}
