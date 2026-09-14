package com.evops.geothermal.enums;

/**
 * 逐行导入结果：
 * SUCCESS（新增落库）、UPDATED（业务键已存在，幂等更新）、FAILED（校验/隔离/锁定拒绝）。
 */
public enum ImportRowResult {
    SUCCESS,
    UPDATED,
    FAILED
}
