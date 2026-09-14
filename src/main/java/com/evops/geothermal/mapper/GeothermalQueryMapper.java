package com.evops.geothermal.mapper;

import com.evops.geothermal.dto.OperationBatchRow;
import com.evops.geothermal.dto.OperationSearchParams;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 运营检索 Mapper：主表 t_monitor_batch 单表为驱动，
 * 多对一维度走 JOIN（不放大），一对多井口压力走 EXISTS/标量子查询（不放大）。
 */
@Mapper
public interface GeothermalQueryMapper {

    /** 命中记录（按 biz_date DESC, id ASC 确定性排序，keyset/offset 分页）。 */
    List<OperationBatchRow> searchBatches(@Param("p") OperationSearchParams params);

    /** 满足同样 AND/范围条件的总数。 */
    long countBatches(@Param("p") OperationSearchParams params);
}
