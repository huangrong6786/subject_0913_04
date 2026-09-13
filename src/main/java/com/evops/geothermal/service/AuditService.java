package com.evops.geothermal.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BizException;
import com.evops.common.RequestContext;
import com.evops.geothermal.entity.BizWriteAudit;
import com.evops.geothermal.mapper.BizWriteAuditMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 跨表写入审计：每个写请求在同一事务内落一条审计，
 * 保存请求号、操作者、业务时区、版本快照。
 * request_no 唯一索引同时承担并发去重/幂等闸门。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final BizWriteAuditMapper auditMapper;
    private final ObjectMapper objectMapper;

    public AuditService(BizWriteAuditMapper auditMapper, ObjectMapper objectMapper) {
        this.auditMapper = auditMapper;
        this.objectMapper = objectMapper;
    }

    public static Map<String, Object> snapshot(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    public String toJson(Object snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception ex) {
            log.warn("版本快照序列化失败", ex);
            return String.valueOf(snapshot);
        }
    }

    /** 同步预检：请求号已被已提交事务使用时快速失败 */
    public void assertRequestNotProcessed(String requestNo) {
        Long count = auditMapper.selectCount(
                new QueryWrapper<BizWriteAudit>().eq("request_no", requestNo));
        if (count != null && count > 0) {
            throw new BizException("DUPLICATE_REQUEST", "请求号已处理，请勿重复提交: " + requestNo);
        }
    }

    /**
     * 在当前事务内写入审计（调用方 GeothermalService 的 @Transactional 事务，传播 REQUIRED 语义）。
     * 并发同请求号时由唯一索引兜底，抛 DuplicateKeyException 使整个事务回滚。
     */
    public BizWriteAudit record(String objectType, Long objectId, String action, Object versionSnapshot) {
        RequestContext.Ctx ctx = RequestContext.get();
        if (ctx == null || ctx.getRequestNo() == null) {
            throw new BizException("缺少请求上下文（请求号）");
        }
        assertRequestNotProcessed(ctx.getRequestNo());

        BizWriteAudit audit = new BizWriteAudit();
        audit.setRequestNo(ctx.getRequestNo());
        audit.setOperatorId(ctx.getOperatorId());
        audit.setOperatorName(ctx.getOperatorName());
        audit.setBizTimezone(ctx.getBizTimezone() == null ? "Asia/Shanghai" : ctx.getBizTimezone());
        audit.setObjectType(objectType);
        audit.setObjectId(objectId);
        audit.setAction(action);
        audit.setVersionSnapshot(toJson(versionSnapshot));
        auditMapper.insert(audit);
        return audit;
    }
}
