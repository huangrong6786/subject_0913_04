package com.evops.geothermal.dto;

import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 试验窗口时序规则计算请求。
 * windowStart/windowEnd 为井场时区下的挂钟时间，区间左闭右开 [windowStart, windowEnd)；
 * 跨午夜窗口合法（如 22:00-次日 06:00），观测只按所属窗口计量一次。
 */
public class TouCalcRequest {
    @NotNull
    private String targetType;             // MONITOR_POINT / WELL_GROUP
    @NotNull
    private Long targetId;
    @NotNull
    private LocalDateTime windowStart;     // 含
    @NotNull
    private LocalDateTime windowEnd;       // 不含

    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public LocalDateTime getWindowStart() { return windowStart; }
    public void setWindowStart(LocalDateTime windowStart) { this.windowStart = windowStart; }
    public LocalDateTime getWindowEnd() { return windowEnd; }
    public void setWindowEnd(LocalDateTime windowEnd) { this.windowEnd = windowEnd; }
}
