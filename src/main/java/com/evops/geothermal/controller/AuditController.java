package com.evops.geothermal.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.ApiResponse;
import com.evops.geothermal.entity.BizWriteAudit;
import com.evops.geothermal.mapper.BizWriteAuditMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 跨表写入审计查询：请求号、操作者、业务时区、版本快照 */
@RestController
@RequestMapping("/api/audits")
public class AuditController {

    private final BizWriteAuditMapper auditMapper;

    public AuditController(BizWriteAuditMapper auditMapper) {
        this.auditMapper = auditMapper;
    }

    @GetMapping
    public ApiResponse<List<BizWriteAudit>> list(@RequestParam(required = false) String requestNo,
                                                 @RequestParam(required = false) String objectType) {
        QueryWrapper<BizWriteAudit> qw = new QueryWrapper<>();
        if (requestNo != null && !requestNo.trim().isEmpty()) {
            qw.eq("request_no", requestNo);
        }
        if (objectType != null && !objectType.trim().isEmpty()) {
            qw.eq("object_type", objectType);
        }
        return ApiResponse.ok(auditMapper.selectList(qw.orderByDesc("id")));
    }
}
