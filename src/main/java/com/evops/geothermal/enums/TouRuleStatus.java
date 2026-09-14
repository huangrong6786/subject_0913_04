package com.evops.geothermal.enums;

/**
 * 规则版本生命周期：
 * DRAFT（草稿，可维护区间）→ ENABLED（启用冻结，不可原地修改）→ SUPERSEDED（被新版本闭合）。
 */
public enum TouRuleStatus {
    DRAFT,
    ENABLED,
    SUPERSEDED;

    /** 已冻结：区间与快照永不可改。 */
    public boolean isFrozen() {
        return this != DRAFT;
    }
}
