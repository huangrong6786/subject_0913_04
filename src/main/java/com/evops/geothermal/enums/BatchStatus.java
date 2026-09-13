package com.evops.geothermal.enums;

/**
 * 监测批次状态流转：
 * DRAFT（草稿） -> RECORDED（已记录，压力/温度/流量/回灌量已原子落库）
 *              -> ACCEPTED（已验收，冻结版本快照，不可删除）
 *              -> ACCOUNTED（已落账，不可删除）
 * 仅允许相邻/向前流转，不允许回退。
 */
public enum BatchStatus {
    DRAFT,
    RECORDED,
    ACCEPTED,
    ACCOUNTED;

    /** 是否不允许直接删除 */
    public boolean isDeleteProtected() {
        return this == ACCEPTED || this == ACCOUNTED;
    }
}
