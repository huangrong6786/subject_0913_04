package com.evops.geothermal.mapper;

import com.evops.geothermal.dto.TelemetryAggParams;
import com.evops.geothermal.dto.TelemetryBucketRow;
import com.evops.geothermal.dto.WellGroupPressureParams;
import com.evops.geothermal.dto.WellGroupPressureRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 秒级遥测聚合 Mapper：
 *  - 所有语句都强制 partition_date 范围谓词（分区裁剪）与租户谓词；
 *  - 井组压力汇总为单条 GROUP BY，调用方不得逐井循环查询。
 */
@Mapper
public interface TelemetryQueryMapper {

    List<TelemetryBucketRow> aggregateBuckets(@Param("p") TelemetryAggParams params);

    List<WellGroupPressureRow> wellGroupPressure(@Param("p") WellGroupPressureParams params);
}
