package com.evops.geothermal.service;

import com.evops.common.BizException;
import com.evops.geothermal.dto.OperationBatchQuery;
import com.evops.geothermal.dto.OperationBatchRow;
import com.evops.geothermal.dto.OperationSearchParams;
import com.evops.geothermal.dto.PageCursor;
import com.evops.geothermal.dto.PageResult;
import com.evops.geothermal.enums.BatchStatus;
import com.evops.geothermal.mapper.GeothermalQueryMapper;
import com.evops.geothermal.security.DataPermissionService;
import com.evops.geothermal.security.DataScope;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 地热井回灌试验 / 井口监测运营检索。
 *
 * 硬约束：
 *  - pageSize 仅允许 1-100；
 *  - 每次查询都施加租户 + 角色数据权限（在 SQL 内强制，不可被入参绕过）；
 *  - 支持至少 4 个条件的 AND/范围组合（井组、试验段、状态、日期区间、井口压力区间）；
 *  - 关联一对多读数时用 EXISTS 半连接，主表批次不放大、不重复；
 *  - 确定性排序 (biz_date DESC, id ASC)，游标或确定性主键补齐分页，100k 量级稳定不重不漏。
 */
@Service
public class OperationQueryService {

    public static final int MIN_PAGE_SIZE = 1;
    public static final int MAX_PAGE_SIZE = 100;

    private final GeothermalQueryMapper queryMapper;
    private final DataPermissionService dataPermissionService;

    public OperationQueryService(GeothermalQueryMapper queryMapper,
                                 DataPermissionService dataPermissionService) {
        this.queryMapper = queryMapper;
        this.dataPermissionService = dataPermissionService;
    }

    public PageResult<OperationBatchRow> searchBatches(OperationBatchQuery q) {
        int pageSize = normalizePageSize(q.getPageSize());
        validateStatus(q.getStatus());
        validatePressureRange(q);
        validateDateRange(q);

        DataScope scope = dataPermissionService.currentScope();
        OperationSearchParams p = OperationSearchParams.of(q, scope);

        // 多取 1 条判定是否还有下一页（游标与页码两种模式通用）
        p.setLimit(pageSize + 1);

        PageCursor cursor = PageCursor.decode(q.getCursor());
        Integer page = q.getPage() == null || q.getPage() < 1 ? 1 : q.getPage();
        if (cursor != null) {
            // keyset：以上一页最后一条的 (bizDate,id) 为锚点，offset 恒 0
            p.setCursorBizDate(cursor.getBizDate());
            p.setCursorId(cursor.getId());
            p.setOffset(0);
            page = null; // 游标模式不回传页码
        } else {
            p.setOffset((page - 1) * pageSize);
        }

        List<OperationBatchRow> rows = queryMapper.searchBatches(p);
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }

        long total = queryMapper.countBatches(p);

        String nextCursor = null;
        if (hasMore && !rows.isEmpty()) {
            OperationBatchRow last = rows.get(rows.size() - 1);
            nextCursor = PageCursor.encode(last.getBizDate(), last.getId());
        }
        return new PageResult<>(rows, total, pageSize, page, nextCursor, hasMore);
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null) {
            return 20;
        }
        if (pageSize < MIN_PAGE_SIZE || pageSize > MAX_PAGE_SIZE) {
            throw new BizException("VALIDATION", "pageSize 只能为 1-100");
        }
        return pageSize;
    }

    private void validateStatus(String status) {
        if (status == null || status.trim().isEmpty()) {
            return;
        }
        try {
            BatchStatus.valueOf(status.trim());
        } catch (IllegalArgumentException ex) {
            throw new BizException("VALIDATION", "非法批次状态: " + status);
        }
    }

    private void validatePressureRange(OperationBatchQuery q) {
        if (q.getPressureMin() != null && q.getPressureMin().compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException("VALIDATION", "井口压力下限不能为负");
        }
        if (q.getPressureMin() != null && q.getPressureMax() != null
                && q.getPressureMin().compareTo(q.getPressureMax()) > 0) {
            throw new BizException("VALIDATION", "井口压力区间非法：下限大于上限");
        }
    }

    private void validateDateRange(OperationBatchQuery q) {
        if (q.getBizDateFrom() != null && q.getBizDateTo() != null
                && q.getBizDateFrom().isAfter(q.getBizDateTo())) {
            throw new BizException("VALIDATION", "业务日期区间非法：起始晚于结束");
        }
    }
}
