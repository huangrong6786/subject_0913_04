package com.evops.geothermal.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 遥测聚合结果：除时间桶外回传实际生效的分区范围，
 * 证明 partition_date 裁剪谓词被强制应用（而不是全表聚合）。
 */
public class TelemetryAggregateResult {
    private LocalDate partitionDateFrom;
    private LocalDate partitionDateTo;
    private String granularity;
    private int bucketSeconds;
    private long totalSamples;
    private List<TelemetryBucketRow> buckets;

    public TelemetryAggregateResult(LocalDate partitionDateFrom, LocalDate partitionDateTo,
                                    String granularity, int bucketSeconds,
                                    long totalSamples, List<TelemetryBucketRow> buckets) {
        this.partitionDateFrom = partitionDateFrom;
        this.partitionDateTo = partitionDateTo;
        this.granularity = granularity;
        this.bucketSeconds = bucketSeconds;
        this.totalSamples = totalSamples;
        this.buckets = buckets;
    }

    public LocalDate getPartitionDateFrom() { return partitionDateFrom; }
    public LocalDate getPartitionDateTo() { return partitionDateTo; }
    public String getGranularity() { return granularity; }
    public int getBucketSeconds() { return bucketSeconds; }
    public long getTotalSamples() { return totalSamples; }
    public List<TelemetryBucketRow> getBuckets() { return buckets; }
}
