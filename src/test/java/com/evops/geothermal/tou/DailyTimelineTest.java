package com.evops.geothermal.tou;

import com.evops.common.BizException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 日周期圆环时间线纯逻辑单测：左闭右开、跨午夜实例与 dayOffset、重叠拒绝、全覆盖校验。
 */
class DailyTimelineTest {

    private DailyIntervalSpec iv(String code, String seg, int s, int e, String coeff) {
        return new DailyIntervalSpec(code, seg, s, e, new BigDecimal(coeff));
    }

    /** 标准 4 段（谷两段，覆盖全天，端点相接）：谷 00-06 / 平 06-18 / 峰 18-22 / 谷 22-24。 */
    private List<DailyIntervalSpec> standard() {
        return Arrays.asList(
                iv("VALLEY_AM", "VALLEY", 0, 360, "0.5"),
                iv("FLAT_DAY", "FLAT", 360, 1080, "1.0"),
                iv("PEAK_EVE", "PEAK", 1080, 1320, "1.5"),
                iv("VALLEY_PM", "VALLEY", 1320, 1440, "0.5"));
    }

    /** 跨午夜三段：谷 22:00-次日06:00（跨午夜）/ 平 06-18 / 峰 18-22。 */
    private List<DailyIntervalSpec> crossMidnight() {
        return Arrays.asList(
                iv("VALLEY_NIGHT", "VALLEY", 1320, 360, "0.5"),
                iv("FLAT_DAY", "FLAT", 360, 1080, "1.0"),
                iv("PEAK_EVE", "PEAK", 1080, 1320, "1.5"));
    }

    @Test
    void validateCoverage_acceptsTouchingEndpoints_andFullDay() {
        assertDoesNotThrow(() -> DailyTimeline.validateCoverage(standard()));
        assertDoesNotThrow(() -> DailyTimeline.validateCoverage(crossMidnight()));
        // 单区间全天（start=0,end=1440）长度为 1440
        DailyIntervalSpec allDay = iv("ALL", "FLAT", 0, 1440, "1.0");
        assertEquals(1440, allDay.lengthMinutes());
        DailyTimeline.validateCoverage(Arrays.asList(allDay));
    }

    @Test
    void validateCoverage_rejectsOverlap_includingCrossMidnight() {
        // 普通重叠（端点相接合法，分钟相交非法）
        BizException ex = assertThrows(BizException.class, () -> DailyTimeline.validateCoverage(Arrays.asList(
                iv("V", "VALLEY", 0, 360, "0.5"),
                iv("F", "FLAT", 359, 1080, "1.0"),
                iv("P", "PEAK", 1080, 1320, "1.5"),
                iv("V2", "VALLEY", 1320, 1440, "0.5"))));
        assertEquals("INTERVAL_OVERLAP", ex.getCode());

        // 跨午夜谷段 [1320,360) 与从 300 分开始的平段重叠（05:00-06:00 相交）
        BizException cross = assertThrows(BizException.class, () -> DailyTimeline.validateCoverage(Arrays.asList(
                iv("VALLEY_NIGHT", "VALLEY", 1320, 360, "0.5"),
                iv("F", "FLAT", 300, 1080, "1.0"),
                iv("P", "PEAK", 1080, 1320, "1.5"))));
        assertEquals("INTERVAL_OVERLAP", cross.getCode());
    }

    @Test
    void validateCoverage_rejectsGap() {
        BizException ex = assertThrows(BizException.class, () -> DailyTimeline.validateCoverage(Arrays.asList(
                iv("V", "VALLEY", 0, 360, "0.5"),
                iv("F", "FLAT", 360, 1080, "1.0"),
                iv("P", "PEAK", 1080, 1320, "1.5")))); // 22:00-24:00 空隙
        assertEquals("INTERVAL_NOT_COVERED", ex.getCode());
    }

    @Test
    void validateCoverage_rejectsDuplicatedCodeAndBadBounds() {
        assertEquals("INTERVAL_CODE_DUPLICATED", assertThrows(BizException.class,
                () -> DailyTimeline.validateCoverage(standardReordered("FLAT_DAY"))).getCode());
        assertEquals("VALIDATION", assertThrows(BizException.class, () ->
                DailyTimeline.validateBounds(1440, 0)).getCode());
        assertEquals("VALIDATION", assertThrows(BizException.class, () ->
                DailyTimeline.validateBounds(360, 360)).getCode()); // 零长度
    }

    private List<DailyIntervalSpec> standardReordered(String dupCode) {
        List<DailyIntervalSpec> list = new ArrayList<>(standard());
        list.add(iv(dupCode, "PEAK", 600, 660, "2")); // 编码重复且内容重叠
        return list;
    }

    @Test
    void match_leftClosedRightOpen_atSegmentBoundaries() {
        List<DailyIntervalSpec> specs = standard();
        LocalDateTime day = LocalDateTime.of(2026, 9, 10, 0, 0);
        // 06:00 整点归入平段（谷段终点不含）
        DailyTimeline.Match atSix = DailyTimeline.match(day.withHour(6), specs);
        assertEquals("FLAT", atSix.getSpec().getSegmentType());
        assertEquals(day.withHour(6), atSix.getSegmentStart());
        assertEquals(day.withHour(18), atSix.getSegmentEnd());
        // 05:59:59 仍在谷段（秒不改变分钟归属）
        DailyTimeline.Match beforeSix = DailyTimeline.match(day.withHour(5).withMinute(59).withSecond(59), specs);
        assertEquals("VALLEY", beforeSix.getSpec().getSegmentType());
        // 00:00 整点含在凌晨谷段；23:59:59 在夜间谷段
        assertEquals("VALLEY", DailyTimeline.match(day, specs).getSpec().getSegmentType());
        assertEquals("VALLEY", DailyTimeline.match(day.withHour(23).withMinute(59).withSecond(59), specs)
                .getSpec().getSegmentType());
        // 18:00 归入峰段（平段终点不含）
        assertEquals("PEAK", DailyTimeline.match(day.withHour(18), specs).getSpec().getSegmentType());
        // 22:00 归入谷段（峰段终点不含）
        assertEquals("VALLEY", DailyTimeline.match(day.withHour(22), specs).getSpec().getSegmentType());
    }

    @Test
    void match_crossMidnightAssignsPreviousDayInstance_withMinusOneOffset() {
        List<DailyIntervalSpec> specs = crossMidnight();
        LocalDateTime d1 = LocalDateTime.of(2026, 9, 10, 0, 0);

        // 9/10 22:30：当日启动的跨午夜谷段实例
        DailyTimeline.Match night = DailyTimeline.match(d1.withHour(22).withMinute(30), specs);
        assertEquals("VALLEY_NIGHT", night.getSpec().getIntervalCode());
        assertEquals(0, night.getDayOffset());
        assertEquals(d1.withHour(22), night.getSegmentStart());
        assertEquals(d1.plusDays(1).withHour(6), night.getSegmentEnd());

        // 9/11 00:30：同一跨午夜实例的早段，dayOffset=-1，归属业务日期 9/10，不重复计量
        DailyTimeline.Match early = DailyTimeline.match(d1.plusDays(1).withHour(0).withMinute(30), specs);
        assertEquals("VALLEY_NIGHT", early.getSpec().getIntervalCode());
        assertEquals(-1, early.getDayOffset());
        assertEquals(d1.withHour(22), early.getSegmentStart());
        assertEquals(d1.plusDays(1).withHour(6), early.getSegmentEnd());
        assertEquals(d1.toLocalDate(), d1.plusDays(1).withHour(0).withMinute(30).toLocalDate()
                .plusDays(early.getDayOffset()));

        // 9/11 06:00：左闭右开，离开谷段进入平段
        DailyTimeline.Match six = DailyTimeline.match(d1.plusDays(1).withHour(6), specs);
        assertEquals("FLAT", six.getSpec().getSegmentType());
        assertEquals(0, six.getDayOffset());
    }
}
