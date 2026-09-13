package com.evops.geothermal.controller;

import com.evops.common.ApiResponse;
import com.evops.geothermal.dto.BatchCreateRequest;
import com.evops.geothermal.dto.BatchDetailView;
import com.evops.geothermal.dto.MonitorPointRequest;
import com.evops.geothermal.dto.ReadingSubmitRequest;
import com.evops.geothermal.dto.ShiftRequest;
import com.evops.geothermal.dto.TestSectionRequest;
import com.evops.geothermal.dto.WellGroupRequest;
import com.evops.geothermal.entity.MonitorBatch;
import com.evops.geothermal.entity.MonitorPoint;
import com.evops.geothermal.entity.ReinjectionShift;
import com.evops.geothermal.entity.TestSection;
import com.evops.geothermal.entity.WellGroup;
import com.evops.geothermal.service.GeothermalService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 地热井回灌试验与井口监测 REST 接口。
 * 写请求需携带 X-Request-No（请求号）、X-Operator-Id/Name（操作者）、X-Biz-Timezone（业务时区）。
 */
@RestController
@RequestMapping("/api")
public class GeothermalController {

    private final GeothermalService service;

    public GeothermalController(GeothermalService service) {
        this.service = service;
    }

    // ---------------- 井组 ----------------

    @PostMapping("/well-groups")
    public ApiResponse<WellGroup> createGroup(@Valid @RequestBody WellGroupRequest req) {
        return ApiResponse.ok(service.createGroup(req));
    }

    @GetMapping("/well-groups")
    public ApiResponse<List<WellGroup>> listGroups() {
        return ApiResponse.ok(service.listGroups());
    }

    @DeleteMapping("/well-groups/{id}")
    public ApiResponse<Void> deleteGroup(@PathVariable Long id) {
        service.deleteGroup(id);
        return ApiResponse.ok(null);
    }

    // ---------------- 试验段 ----------------

    @PostMapping("/test-sections")
    public ApiResponse<TestSection> createSection(@Valid @RequestBody TestSectionRequest req) {
        return ApiResponse.ok(service.createSection(req));
    }

    @GetMapping("/test-sections")
    public ApiResponse<List<TestSection>> listSections(@RequestParam(required = false) Long groupId) {
        return ApiResponse.ok(service.listSections(groupId));
    }

    @DeleteMapping("/test-sections/{id}")
    public ApiResponse<Void> deleteSection(@PathVariable Long id) {
        service.deleteSection(id);
        return ApiResponse.ok(null);
    }

    // ---------------- 监测点 ----------------

    @PostMapping("/monitor-points")
    public ApiResponse<MonitorPoint> createPoint(@Valid @RequestBody MonitorPointRequest req) {
        return ApiResponse.ok(service.createPoint(req));
    }

    @GetMapping("/monitor-points")
    public ApiResponse<List<MonitorPoint>> listPoints(@RequestParam(required = false) Long sectionId) {
        return ApiResponse.ok(service.listPoints(sectionId));
    }

    @DeleteMapping("/monitor-points/{id}")
    public ApiResponse<Void> deletePoint(@PathVariable Long id) {
        service.deletePoint(id);
        return ApiResponse.ok(null);
    }

    // ---------------- 回灌班次 ----------------

    @PostMapping("/shifts")
    public ApiResponse<ReinjectionShift> createShift(@Valid @RequestBody ShiftRequest req) {
        return ApiResponse.ok(service.createShift(req));
    }

    @GetMapping("/shifts")
    public ApiResponse<List<ReinjectionShift>> listShifts(
            @RequestParam(required = false) Long sectionId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate shiftDate) {
        return ApiResponse.ok(service.listShifts(sectionId, shiftDate));
    }

    @PostMapping("/shifts/{id}/close")
    public ApiResponse<ReinjectionShift> closeShift(@PathVariable Long id) {
        return ApiResponse.ok(service.closeShift(id));
    }

    @DeleteMapping("/shifts/{id}")
    public ApiResponse<Void> deleteShift(@PathVariable Long id) {
        service.deleteShift(id);
        return ApiResponse.ok(null);
    }

    // ---------------- 监测批次 ----------------

    /** 批次建立：支持 shiftId+monitorPointId 或 井组/试验段/班次复合键分量 */
    @PostMapping("/batches")
    public ApiResponse<MonitorBatch> createBatch(@Valid @RequestBody BatchCreateRequest req) {
        return ApiResponse.ok(service.createBatch(req));
    }

    @GetMapping("/batches")
    public ApiResponse<List<MonitorBatch>> listBatches(@RequestParam(required = false) String status) {
        return ApiResponse.ok(service.listBatches(status));
    }

    @GetMapping("/batches/{id}")
    public ApiResponse<BatchDetailView> getBatch(@PathVariable Long id) {
        return ApiResponse.ok(service.getBatchDetail(id));
    }

    /** 复合键关联查询：井组 + 试验段 + 业务日期 + 班次序号 + 监测点编码 */
    @GetMapping("/batches/by-key")
    public ApiResponse<BatchDetailView> getBatchByKey(
            @RequestParam String groupCode,
            @RequestParam String sectionCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate shiftDate,
            @RequestParam(required = false, defaultValue = "1") Integer shiftIndex,
            @RequestParam String pointCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
        return ApiResponse.ok(service.getBatchDetailByCompositeKey(
                groupCode, sectionCode, shiftDate, shiftIndex, pointCode, bizDate));
    }

    @DeleteMapping("/batches/{id}")
    public ApiResponse<Void> deleteBatch(@PathVariable Long id) {
        service.deleteBatch(id);
        return ApiResponse.ok(null);
    }

    /** 压力/温度/流量/回灌量同批次原子落库 */
    @PostMapping("/batches/{id}/readings")
    public ApiResponse<MonitorBatch> submitReadings(@PathVariable Long id,
                                                    @Valid @RequestBody ReadingSubmitRequest req) {
        return ApiResponse.ok(service.submitReadings(id, req));
    }

    /** 状态流转：RECORDED -> ACCEPTED */
    @PostMapping("/batches/{id}/accept")
    public ApiResponse<MonitorBatch> acceptBatch(@PathVariable Long id) {
        return ApiResponse.ok(service.acceptBatch(id));
    }

    /** 状态流转：ACCEPTED -> ACCOUNTED（落账） */
    @PostMapping("/batches/{id}/account")
    public ApiResponse<MonitorBatch> accountBatch(@PathVariable Long id,
                                                  @RequestBody Map<String, Long> body) {
        Long accountingId = body == null ? null : body.get("accountingId");
        return ApiResponse.ok(service.accountBatch(id, accountingId));
    }
}
