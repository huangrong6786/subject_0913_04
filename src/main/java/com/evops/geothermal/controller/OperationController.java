package com.evops.geothermal.controller;

import com.evops.common.ApiResponse;
import com.evops.geothermal.dto.OperationBatchQuery;
import com.evops.geothermal.dto.OperationBatchRow;
import com.evops.geothermal.dto.PageResult;
import com.evops.geothermal.service.OperationQueryService;
import com.evops.geothermal.service.TelemetryQueryService;
import com.evops.geothermal.dto.TelemetryAggregateQuery;
import com.evops.geothermal.dto.TelemetryAggregateResult;
import com.evops.geothermal.dto.WellGroupPressureRow;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 地热井回灌试验与井口监测「运营检索」接口。
 * 全部经过 Shiro HTTP Basic；服务层在每次查询强制施加租户 + 角色数据权限。
 */
@RestController
@RequestMapping("/api/operations")
public class OperationController {

    private final OperationQueryService operationQueryService;
    private final TelemetryQueryService telemetryQueryService;

    public OperationController(OperationQueryService operationQueryService,
                               TelemetryQueryService telemetryQueryService) {
        this.operationQueryService = operationQueryService;
        this.telemetryQueryService = telemetryQueryService;
    }

    /**
     * 回灌批次组合检索：井组 / 试验段 / 状态 / 业务日期区间 / 井口压力区间，
     * 返回稳定排序分页结果与总数；支持 cursor 或 page 翻页，pageSize 1-100。
     */
    @GetMapping("/batches")
    public ApiResponse<PageResult<OperationBatchRow>> searchBatches(
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) Long sectionId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDateTo,
            @RequestParam(required = false) java.math.BigDecimal pressureMin,
            @RequestParam(required = false) java.math.BigDecimal pressureMax,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) String cursor) {

        OperationBatchQuery q = new OperationBatchQuery();
        q.setGroupId(groupId);
        q.setSectionId(sectionId);
        q.setStatus(status);
        q.setBizDateFrom(bizDateFrom);
        q.setBizDateTo(bizDateTo);
        q.setPressureMin(pressureMin);
        q.setPressureMax(pressureMax);
        q.setPageSize(pageSize);
        q.setPage(page);
        q.setCursor(cursor);
        return ApiResponse.ok(operationQueryService.searchBatches(q));
    }

    /**
     * 秒级井口遥测时间桶聚合（强制分区日期裁剪）。
     * 查询参数绑定到 TelemetryAggregateQuery：groupId/monitorPointId/dateFrom/dateTo/granularity。
     */
    @GetMapping("/telemetry/aggregate")
    public ApiResponse<TelemetryAggregateResult> aggregateTelemetry(TelemetryAggregateQuery query) {
        return ApiResponse.ok(telemetryQueryService.aggregate(query));
    }

    /**
     * 40 井组压测：单条 GROUP BY SQL 产出全部授权井组的压力汇总，禁止逐井 N+1。
     */
    @GetMapping("/telemetry/well-group-pressure")
    public ApiResponse<List<WellGroupPressureRow>> wellGroupPressure(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) List<Long> groupIds) {
        return ApiResponse.ok(telemetryQueryService.wellGroupPressure(dateFrom, dateTo, groupIds));
    }
}
