package com.evops.geothermal.enums;

/**
 * 业务时段类型：峰值 / 平段 / 谷值。
 * 运营人员至少维护这三类规则区间（或等价业务区间）。
 */
public enum SegmentType {
    /** 峰值 */
    PEAK,
    /** 平段 */
    FLAT,
    /** 谷值 */
    VALLEY
}
