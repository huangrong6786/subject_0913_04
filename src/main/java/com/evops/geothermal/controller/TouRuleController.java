package com.evops.geothermal.controller;

import com.evops.common.ApiResponse;
import com.evops.geothermal.dto.TouChainCreateRequest;
import com.evops.geothermal.dto.TouIntervalUpdateRequest;
import com.evops.geothermal.dto.TouVersionCloneRequest;
import com.evops.geothermal.dto.TouVersionDraftRequest;
import com.evops.geothermal.entity.TouInterval;
import com.evops.geothermal.entity.TouRuleChain;
import com.evops.geothermal.entity.TouRuleSet;
import com.evops.geothermal.service.TouRuleService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 井口监测时序规则（峰/平/谷）版本化维护 REST 接口。
 * 已启用版本冻结，所有写请求携带 X-Request-No / X-Operator-Id / X-Biz-Timezone 头。
 */
@RestController
@RequestMapping("/api/tou")
public class TouRuleController {

    private final TouRuleService ruleService;

    public TouRuleController(TouRuleService ruleService) {
        this.ruleService = ruleService;
    }

    // ---------------- 规则链 ----------------

    @PostMapping("/chains")
    public ApiResponse<TouRuleChain> createChain(@Valid @RequestBody TouChainCreateRequest req) {
        return ApiResponse.ok(ruleService.createChain(req));
    }

    @GetMapping("/chains")
    public ApiResponse<List<TouRuleChain>> listChains(@RequestParam(required = false) String targetType,
                                                      @RequestParam(required = false) Long targetId) {
        return ApiResponse.ok(ruleService.listChains(targetType, targetId));
    }

    @GetMapping("/chains/{id}")
    public ApiResponse<TouRuleChain> getChain(@PathVariable Long id) {
        return ApiResponse.ok(ruleService.getChain(id));
    }

    // ---------------- 版本（草稿/克隆/启用/删除） ----------------

    /** 新建草稿版本（携带初始峰平谷区间；启用前可继续增删改）。 */
    @PostMapping("/chains/{id}/versions")
    public ApiResponse<TouRuleSet> createDraft(@PathVariable Long id,
                                               @Valid @RequestBody TouVersionDraftRequest req) {
        return ApiResponse.ok(ruleService.createDraft(id, req));
    }

    @GetMapping("/chains/{id}/versions")
    public ApiResponse<List<TouRuleSet>> listVersions(@PathVariable Long id) {
        return ApiResponse.ok(ruleService.listVersions(id));
    }

    /** 基于历史版本开新草稿（规则不能原地修改，只能迭代新版本）。 */
    @PostMapping("/versions/{setId}/clone")
    public ApiResponse<TouRuleSet> cloneVersion(@PathVariable Long setId,
                                                @Valid @RequestBody TouVersionCloneRequest req) {
        return ApiResponse.ok(ruleService.cloneAsDraft(setId, req));
    }

    @GetMapping("/versions/{setId}")
    public ApiResponse<Map<String, Object>> getVersion(@PathVariable Long setId) {
        return ApiResponse.ok(ruleService.getVersionDetail(setId));
    }

    /** 草稿 → 启用冻结（重叠/未覆盖/缺峰平谷一律拒绝）。 */
    @PostMapping("/versions/{setId}/enable")
    public ApiResponse<TouRuleSet> enable(@PathVariable Long setId) {
        return ApiResponse.ok(ruleService.enableVersion(setId));
    }

    /** 删除草稿（已启用版本永久保留，拒绝删除）。 */
    @DeleteMapping("/versions/{setId}")
    public ApiResponse<Void> deleteDraft(@PathVariable Long setId) {
        ruleService.deleteDraft(setId);
        return ApiResponse.ok(null);
    }

    // ---------------- 草稿区间维护 ----------------

    @PostMapping("/versions/{setId}/intervals/{intervalCode}")
    public ApiResponse<TouInterval> addInterval(@PathVariable Long setId,
                                                @PathVariable String intervalCode,
                                                @Valid @RequestBody TouIntervalUpdateRequest req) {
        return ApiResponse.ok(ruleService.addInterval(setId, req, intervalCode));
    }

    @PutMapping("/versions/{setId}/intervals/{intervalCode}")
    public ApiResponse<TouInterval> updateInterval(@PathVariable Long setId,
                                                   @PathVariable String intervalCode,
                                                   @Valid @RequestBody TouIntervalUpdateRequest req) {
        return ApiResponse.ok(ruleService.updateInterval(setId, intervalCode, req));
    }

    @DeleteMapping("/versions/{setId}/intervals/{intervalCode}")
    public ApiResponse<Void> deleteInterval(@PathVariable Long setId,
                                            @PathVariable String intervalCode) {
        ruleService.deleteInterval(setId, intervalCode);
        return ApiResponse.ok(null);
    }
}
