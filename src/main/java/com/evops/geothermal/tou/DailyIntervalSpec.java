package com.evops.geothermal.tou;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 一天 1440 分钟“圆环时间线”上的峰平谷区间规格（不可变值对象）。
 * 区间采用左闭右开 [startMinute, endMinute)；endMinute &lt;= startMinute 表示跨午夜
 * （如谷段 22:00-次日 06:00：start=1320, end=360）。
 */
public final class DailyIntervalSpec {
    private final String intervalCode;
    private final String segmentType;
    private final int startMinute;
    private final int endMinute;
    private final BigDecimal priceCoefficient;

    public DailyIntervalSpec(String intervalCode, String segmentType,
                             int startMinute, int endMinute, BigDecimal priceCoefficient) {
        this.intervalCode = intervalCode;
        this.segmentType = segmentType;
        this.startMinute = startMinute;
        this.endMinute = endMinute;
        this.priceCoefficient = priceCoefficient;
    }

    public String getIntervalCode() { return intervalCode; }
    public String getSegmentType() { return segmentType; }
    public int getStartMinute() { return startMinute; }
    public int getEndMinute() { return endMinute; }
    public BigDecimal getPriceCoefficient() { return priceCoefficient; }

    /** 区间长度（分钟）；start=0,end=1440 为全天；跨午夜区间按两段合计。 */
    public int lengthMinutes() {
        return DailyTimeline.lengthMinutes(startMinute, endMinute);
    }

    /** 该区间在直线 [0,2880) 上展开成的 1-2 个左闭右开整数段。 */
    public List<int[]> expandedSegments() {
        return DailyTimeline.expand(startMinute, endMinute);
    }

    public static List<DailyIntervalSpec> copyOf(List<DailyIntervalSpec> source) {
        List<DailyIntervalSpec> copy = new ArrayList<>();
        for (DailyIntervalSpec s : source) {
            copy.add(new DailyIntervalSpec(s.intervalCode, s.segmentType,
                    s.startMinute, s.endMinute, s.priceCoefficient));
        }
        return copy;
    }
}
