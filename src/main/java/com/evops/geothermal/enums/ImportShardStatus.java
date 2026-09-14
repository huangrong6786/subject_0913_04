package com.evops.geothermal.enums;

/**
 * 导入分片状态：
 * PENDING（待处理） -> PROCESSING（处理中） -> SUCCESS（已处理，允许存在行级失败）
 *                                           -> FAILED（分片级失败，可重试）
 * 行级校验失败不会导致分片 FAILED；只有基础设施异常（如数据库不可用）才置 FAILED。
 */
public enum ImportShardStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED
}
