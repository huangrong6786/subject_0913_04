package com.evops.geothermal.controller;

import com.evops.common.ApiResponse;
import com.evops.common.BizException;
import com.evops.geothermal.dto.DeviceQuarantineRequest;
import com.evops.geothermal.dto.ImportRowView;
import com.evops.geothermal.dto.ImportSummaryView;
import com.evops.geothermal.dto.PageResult;
import com.evops.geothermal.entity.DeviceQuarantine;
import com.evops.geothermal.service.DeviceQuarantineService;
import com.evops.geothermal.service.ObservationImportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import java.util.List;

/**
 * 观测数据 CSV 批量导入接口。
 * 文件校验和 + 业务键双重幂等；分片处理（单行失败不回滚整批）；
 * 失败分片可重试、断点续传；坏传感器隔离；已锁定/已验收数据禁止覆盖。
 */
@RestController
@RequestMapping("/api/imports")
public class ImportController {

    private final ObservationImportService importService;
    private final DeviceQuarantineService quarantineService;

    public ImportController(ObservationImportService importService,
                            DeviceQuarantineService quarantineService) {
        this.importService = importService;
        this.quarantineService = quarantineService;
    }

    /**
     * CSV 批量导入（multipart 上传，字段名 file）。
     * 同一文件重复上传命中校验和幂等；存在未完成分片时断点续传。
     */
    @PostMapping("/observations")
    public ApiResponse<ImportSummaryView> importObservations(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("CSV_EMPTY", "上传文件为空");
        }
        try {
            return ApiResponse.ok(importService.importObservations(
                    file.getOriginalFilename(), file.getInputStream()));
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException("CSV_READ_ERROR", "上传文件读取失败: " + ex.getMessage());
        }
    }

    /** 导入任务汇总：总数/成功/更新/失败 + 分片进度。 */
    @GetMapping("/observations/{id}")
    public ApiResponse<ImportSummaryView> getSummary(@PathVariable Long id) {
        return ApiResponse.ok(importService.getSummary(id));
    }

    /** 逐行明细（可按 result=SUCCESS/UPDATED/FAILED 过滤），失败行含字段/原值/原因。 */
    @GetMapping("/observations/{id}/rows")
    public ApiResponse<PageResult<ImportRowView>> getRows(@PathVariable Long id,
                                                          @RequestParam(required = false) String result,
                                                          @RequestParam(required = false) Integer page,
                                                          @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.ok(importService.getRows(id, result, page, pageSize));
    }

    /** 失败分片重试（断点续跑 FAILED/PENDING/PROCESSING 分片，幂等）。 */
    @PostMapping("/observations/{id}/retry")
    public ApiResponse<ImportSummaryView> retry(@PathVariable Long id) {
        return ApiResponse.ok(importService.retryFailedShards(id));
    }

    // ---------------- 坏传感器隔离 ----------------

    /** 隔离来源设备（坏传感器），后续导入逐行拦截该设备的观测行。 */
    @PostMapping("/devices/quarantine")
    public ApiResponse<DeviceQuarantine> quarantine(@Valid @RequestBody DeviceQuarantineRequest req) {
        return ApiResponse.ok(quarantineService.quarantine(req.getDeviceCode(), req.getReason()));
    }

    /** 解除设备隔离。 */
    @PostMapping("/devices/release")
    public ApiResponse<DeviceQuarantine> release(@Valid @RequestBody DeviceQuarantineRequest req) {
        return ApiResponse.ok(quarantineService.release(req.getDeviceCode()));
    }

    /** 当前生效的隔离名单。 */
    @GetMapping("/devices/quarantine")
    public ApiResponse<List<DeviceQuarantine>> listQuarantined() {
        return ApiResponse.ok(quarantineService.listActive());
    }
}
