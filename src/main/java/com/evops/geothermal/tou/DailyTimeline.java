package com.evops.geothermal.tou;

import com.evops.common.BizException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 日周期圆环时间线纯逻辑工具：左闭右开区间的合法性/重叠/全覆盖校验与时刻归类。
 *
 * <ul>
 *   <li>分钟取值：startMinute ∈ [0,1440)，endMinute ∈ (0,1440]，endMinute != startMinute；
 *       start=0,end=1440 表示全天区间；end &lt; start 表示跨午夜。</li>
 *   <li>区间两两不得重叠（左闭右开，端点相接 [..,06:00)+[06:00,..) 合法）。</li>
 *   <li>区间必须完整覆盖 1440 分钟，不留空隙。</li>
 *   <li>归类按业务时区本地挂钟时间；跨午夜区间早段（如 00:30）归入“前一日启动”的实例，
 *       返回相对业务日期的 dayOffset=-1，保证跨午夜观测只计量一次。</li>
 * </ul>
 */
public final class DailyTimeline {

    public static final int MINUTES_PER_DAY = 1440;

    private DailyTimeline() {}

    /** 区间长度（分钟）。 */
    public static int lengthMinutes(int start, int end) {
        if (start == 0 && end == MINUTES_PER_DAY) {
            return MINUTES_PER_DAY;
        }
        return end > start ? end - start : MINUTES_PER_DAY - start + end;
    }

    /** 展开到直线 [0,2880)：跨午夜区间得到 [start,1440)+[1440,1440+end) 两段。 */
    public static List<int[]> expand(int start, int end) {
        List<int[]> ranges = new java.util.ArrayList<>(2);
        if (start == 0 && end == MINUTES_PER_DAY) {
            ranges.add(new int[]{0, MINUTES_PER_DAY});
        } else if (end > start) {
            ranges.add(new int[]{start, end});
        } else {
            ranges.add(new int[]{start, MINUTES_PER_DAY});
            ranges.add(new int[]{MINUTES_PER_DAY, MINUTES_PER_DAY + end});
        }
        return ranges;
    }

    /** 单个区间边界合法性。 */
    public static void validateBounds(int start, int end) {
        if (start < 0 || start >= MINUTES_PER_DAY) {
            throw new BizException("VALIDATION", "区间起点分钟必须在 [0,1440) 内: " + start);
        }
        if (end <= 0 || end > MINUTES_PER_DAY) {
            throw new BizException("VALIDATION", "区间终点分钟必须在 (0,1440] 内: " + end);
        }
        if (start == end) {
            throw new BizException("VALIDATION", "零长度区间非法（起终点同为 " + start + " 分钟）");
        }
    }

    /**
     * 启用前综合校验：边界合法 + 编码不重复 + 两两不重叠 + 完整覆盖 1440 分钟。
     * 抛 BizException 表示拒绝启用。
     */
    public static void validateCoverage(List<DailyIntervalSpec> intervals) {
        if (intervals == null || intervals.isEmpty()) {
            throw new BizException("VALIDATION", "规则版本至少包含一个区间");
        }
        java.util.BitSet covered = new java.util.BitSet(MINUTES_PER_DAY);
        for (int i = 0; i < intervals.size(); i++) {
            DailyIntervalSpec a = intervals.get(i);
            validateBounds(a.getStartMinute(), a.getEndMinute());
            java.util.BitSet aMinutes = minuteSet(a);
            for (int j = 0; j < i; j++) {
                DailyIntervalSpec b = intervals.get(j);
                if (a.getIntervalCode().equals(b.getIntervalCode())) {
                    throw new BizException("INTERVAL_CODE_DUPLICATED",
                            "区间编码在同一版本内重复: " + a.getIntervalCode());
                }
                java.util.BitSet intersect = (java.util.BitSet) aMinutes.clone();
                intersect.and(minuteSet(b));
                if (!intersect.isEmpty()) {
                    throw new BizException("INTERVAL_OVERLAP",
                            "规则区间重叠被拒绝: " + a.getIntervalCode() + " 与 " + b.getIntervalCode());
                }
            }
            covered.or(aMinutes);
        }
        if (covered.cardinality() != MINUTES_PER_DAY) {
            int firstGap = covered.nextClearBit(0);
            throw new BizException("INTERVAL_NOT_COVERED",
                    "规则区间未完整覆盖全天 1440 分钟，自第 " + firstGap + " 分钟起存在空隙");
        }
    }

    /** 左闭右开重叠判定：两区间在圆环分钟集合上有公共分钟。端点相接不算重叠。 */
    public static boolean overlaps(DailyIntervalSpec a, DailyIntervalSpec b) {
        java.util.BitSet intersect = minuteSet(a);
        intersect.and(minuteSet(b));
        return !intersect.isEmpty();
    }

    /** 区间覆盖的圆环分钟集合（跨午夜早段映射回 [0,end)；全天区间占满 1440 分钟）。 */
    private static java.util.BitSet minuteSet(DailyIntervalSpec spec) {
        java.util.BitSet bits = new java.util.BitSet(MINUTES_PER_DAY);
        int s = spec.getStartMinute();
        int e = spec.getEndMinute();
        if (s == 0 && e == MINUTES_PER_DAY) {
            bits.set(0, MINUTES_PER_DAY);
        } else if (e > s) {
            bits.set(s, e);
        } else {
            bits.set(s, MINUTES_PER_DAY);
            bits.set(0, e);
        }
        return bits;
    }

    /**
     * 把对象时区本地时刻归入唯一区间（调用方已保证全覆盖、不重叠）。
     * 返回命中区间及其“绝对实例”边界（LocalDateTime 挂钟）与相对业务日期偏移。
     */
    public static Match match(LocalDateTime time, List<DailyIntervalSpec> intervals) {
        LocalDate date = time.toLocalDate();
        int minute = time.getHour() * 60 + time.getMinute();
        // 秒级观测：同一分钟内只可能命中一个区间；区间按分钟粒度，秒不改变归属。
        for (DailyIntervalSpec spec : intervals) {
            int s = spec.getStartMinute();
            int e = spec.getEndMinute();
            LocalDateTime start;
            LocalDateTime end;
            int dayOffset;
            if (s == 0 && e == MINUTES_PER_DAY) {
                start = date.atStartOfDay();
                end = date.plusDays(1).atStartOfDay();
                dayOffset = 0;
            } else if (e > s) {
                if (minute < s || minute >= e) {
                    continue;
                }
                start = date.atStartOfDay().plusMinutes(s);
                end = date.atStartOfDay().plusMinutes(e);
                dayOffset = 0;
            } else {
                // 跨午夜：[s,1440) 属于当日启动实例；[0,e) 属于前一日启动实例
                if (minute >= s) {
                    start = date.atStartOfDay().plusMinutes(s);
                    end = date.plusDays(1).atStartOfDay().plusMinutes(e);
                    dayOffset = 0;
                } else if (minute < e) {
                    start = date.minusDays(1).atStartOfDay().plusMinutes(s);
                    end = date.atStartOfDay().plusMinutes(e);
                    dayOffset = -1;
                } else {
                    continue;
                }
            }
            if (!time.isBefore(start) && time.isBefore(end)) {
                return new Match(spec, start, end, dayOffset);
            }
        }
        throw new BizException("INTERVAL_NOT_COVERED",
                "时刻 " + time + " 未命中任何规则区间（规则未覆盖全天）");
    }

    /** 命中结果：区间规格 + 实例 [segmentStart, segmentEnd) + dayOffset。 */
    public static final class Match {
        private final DailyIntervalSpec spec;
        private final LocalDateTime segmentStart;
        private final LocalDateTime segmentEnd;
        private final int dayOffset;

        public Match(DailyIntervalSpec spec, LocalDateTime segmentStart,
                     LocalDateTime segmentEnd, int dayOffset) {
            this.spec = spec;
            this.segmentStart = segmentStart;
            this.segmentEnd = segmentEnd;
            this.dayOffset = dayOffset;
        }

        public DailyIntervalSpec getSpec() { return spec; }
        public LocalDateTime getSegmentStart() { return segmentStart; }
        public LocalDateTime getSegmentEnd() { return segmentEnd; }
        public int getDayOffset() { return dayOffset; }
    }
}
