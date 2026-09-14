package com.evops.geothermal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.geothermal.entity.ImportRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ImportRowMapper extends BaseMapper<ImportRow> {

    /** 分片内逐行明细的多行批量插入（单条 SQL 多 VALUES，避免逐行往返）。 */
    int batchInsert(@Param("rows") List<ImportRow> rows);
}
