package com.evops.geothermal.service;

import com.evops.common.BizException;
import com.evops.geothermal.dto.TelemetryAggParams;
import com.evops.geothermal.dto.TelemetryAggregateQuery;
import com.evops.geothermal.dto.TelemetryAggregateResult;
import com.evops.geothermal.dto.TelemetryBucketRow;
import com.evops.geothermal.dto.WellGroupPressureParams;
import com.evops.geothermal.dto.WellGroupPressureRow;
import com.evops.geothermal.mapper.TelemetryQueryMapper;
import com.evops.geothermal.security.DataPermissionService;
import com.evops.geothermal.security.DataScope;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 秒级井口遥测聚合。
 *
 * 硬约束：
 *  - 聚合必须分区裁剪：日期范围必填并下推为 partition_date 范围谓词，且时间窗封顶，
 *    从入口层面杜绝无界全表扫描；
 *  - 40 井组压测由单条 GROUP BY 集合查询完成，禁止逐井 N+1；
 *  - 每次查询都施加租户 + 角色数据权限。
 */
@Service
public class TelemetryQueryService {

    /** 时间桶聚合允许的最大窗口（天），防止全表扫描。 */
    static final long MAX_BUCKET_WINDOW_DAYS = 7;
    /** 井组压力汇总允许的最大窗口（天）。 */
    static final long MAX_PRESSURE_WINDOW_DAYS = 31;

    private static final Map<String, Integer> GRANULARITY_SECONDS = new LinkedHashMap<>();

    static {
        GRANULARITY_SECONDS.put("MINUTE", 60);
        GRANULARITY_SECONDS.put("FIVE_MIN", 300);
        GRANULARITY_SECONDS.put("TEN_MIN", 600);
        GRANULARITY_SECONDS.put("HOUR", 3600);
        GRANULARITY_SECONDS.put("DAY", 86400);
    }

    private final TelemetryQueryMapper telemetryMapper;
    private final DataPermissionService dataPermissionService;

    public TelemetryQueryService(TelemetryQueryMapper telemetryMapper,
                                 DataPermissionService dataPermissionService) {
        this.telemetryMapper = telemetryMapper;
        this.dataPermissionService = dataPermissionService;
    }

    public TelemetryAggregateResult aggregate(TelemetryAggregateQuery q) {
        if (q.getDateFrom() == null || q.getDateTo() == null) {
            throw new BizException("VALIDATION", "遥测聚合必须提供 dateFrom/dateTo 以做分区裁剪");
        }
        validateWindow(q.getDateFrom(), q.getDateTo(), MAX_BUCKET_WINDOW_DAYS);

        String granularity = q.getGranularity() == null || q.getGranularity().trim().isEmpty()
                ? "HOUR" : q.getGranularity().trim().toUpperCase();
        Integer bucketSeconds = GRANULARITY_SECONDS.get(granularity);
        if (bucketSeconds == null) {
            throw new BizException("VALIDATION",
                    "非法粒度: " + q.getGranularity() + "，可选 " + GRANULARITY_SECONDS.keySet());
        }

        DataScope scope = dataPermissionService.currentScope();
        TelemetryAggParams params = TelemetryAggParams.of(q, scope, q.getDateFrom(), q.getDateTo(), bucketSeconds);
        List<TelemetryBucketRow> buckets = telemetryMapper.aggregateBuckets(params);
        long totalSamples = buckets.stream().mapToLong(TelemetryBucketRow::getSampleCount).sum();

        return new TelemetryAggregateResult(q.getDateFrom(), q.getDateTo(), granularity,
                bucketSeconds, totalSamples, buckets);
    }

    /**
     * 40 井组压测：单条集合 GROUP BY 返回全部授权井组压力汇总。
     * 方法内不按井组循环发查询（无 N+1）；groupIds 仅用于在授权范围内进一步收窄。
     */
    public List<WellGroupPressureRow> wellGroupPressure(LocalDate dateFrom, LocalDate dateTo,
                                                        List<Long> groupIds) {
        if (dateFrom == null || dateTo == null) {
            throw new BizException("VALIDATION", "井组压力汇总必须提供 dateFrom/dateTo 以做分区裁剪");
        }
        validateWindow(dateFrom, dateTo, MAX_PRESSURE_WINDOW_DAYS);
        if (groupIds != null) {
            for (Long gid : groupIds) {
                if (gid == null || gid <= 0) {
                    throw new BizException("VALIDATION", "非法井组 ID");
                }
            }
        }
        DataScope scope = dataPermissionService.currentScope();
        WellGroupPressureParams params = WellGroupPressureParams.of(scope, dateFrom, dateTo, groupIds);
        List<WellGroupPressureRow> rows = telemetryMapper.wellGroupPressure(params);
        for (WellGroupPressureRow row : rows) {
            row.setWindowFrom(dateFrom);
            row.setWindowTo(dateTo);
        }
        return rows;
    }

    private void validateWindow(LocalDate from, LocalDate to, long maxDays) {
        if (from.isAfter(to)) {
            throw new BizException("VALIDATION", "日期区间非法：起始晚于结束");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > maxDays) {
            throw new BizException("VALIDATION",
                    "时间窗超过分区裁剪上限 " + maxDays + " 天，请缩小范围后再聚合");
        }
    }

    /** 供文档/接口列出可用粒度。 */
    public static List<String> supportedGranularities() {
        return Arrays.asList(GRANULARITY_SECONDS.keySet().toArray(new String[0]));
    }
}
