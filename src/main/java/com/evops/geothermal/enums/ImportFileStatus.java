package com.evops.geothermal.enums;

/**
 * 导入文件（导入任务）状态：
 * PROCESSING（分片处理中，可断点续传）
 * -> COMPLETED（全部行成功/更新，无失败行）
 * -> COMPLETED_WITH_ERRORS（存在失败行，但所有分片均已处理）
 * -> FAILED（存在基础设施级失败分片，可重试）
 */
public enum ImportFileStatus {
    PROCESSING,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    FAILED
}
