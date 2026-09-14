package com.evops.geothermal.controller;

import com.evops.common.ApiResponse;
import com.evops.geothermal.dto.TouCalcRequest;
import com.evops.geothermal.entity.TouCalcResult;
import com.evops.geothermal.service.TouCalcService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 试验窗口时序规则计算 REST 接口。
 * 窗口按井场时区左闭右开；同一窗口重复/并发提交幂等返回唯一结果。
 */
@RestController
@RequestMapping("/api/tou/calculations")
public class TouCalcController {

    private final TouCalcService calcService;

    public TouCalcController(TouCalcService calcService) {
        this.calcService = calcService;
    }

    /** 执行试验窗口规则计算（已算窗口幂等返回；重叠窗口拒绝）。 */
    @PostMapping
    public ApiResponse<TouCalcResult> calculate(@Valid @RequestBody TouCalcRequest req) {
        return ApiResponse.ok(calcService.calculate(req));
    }

    @GetMapping
    public ApiResponse<List<TouCalcResult>> list(@RequestParam(required = false) String targetType,
                                                 @RequestParam(required = false) Long targetId) {
        return ApiResponse.ok(calcService.listResults(targetType, targetId));
    }

    /** 结果详情：头 + 逐观测明细（历史结果读取当时快照）。 */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable Long id) {
        return ApiResponse.ok(calcService.getResultView(id));
    }
}
