package com.evops.geothermal.bootstrap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 压测演示数据生成器（幂等，可重复触发）。
 *
 * 规模：
 *  - 40 个井组（租户 1 与租户 2 各 20），各 1 试验段 + 1 监测点；
 *  - 40 井组 × 10 个业务日期 = 400 班次 / 400 批次（状态轮换覆盖四种状态）；
 *  - 100,000 条井口读数事件（每批 250 条，压力确定性变化以支撑区间检索）；
 *  - 150,000 条秒级井口遥测（40 井组 × 3 个日期分区 × 1250 秒）；
 *  - t1view 被授权租户 1 的前 10 个井组，用于验证只读账号数据权限。
 *
 * 全部走 JdbcTemplate 批量插入，分块提交，避免单条插入的往返开销。
 */
@Service
public class DemoDataSeeder {

    public static final int GROUP_COUNT = 40;
    public static final int TENANT_GROUPS = 20;
    public static final int BIZ_DATES = 10;
    public static final int READINGS_PER_BATCH = 250;          // 400 * 250 = 100000
    public static final int TELEMETRY_DATES = 3;
    public static final int TELEMETRY_PER_GROUP_DATE = 1250;   // 40*3*1250 = 150000
    public static final String VIEWER_ACCOUNT = "t1view";

    private static final LocalDate BIZ_BASE = LocalDate.of(2026, 9, 1);
    private static final LocalDate TELEMETRY_BASE = LocalDate.of(2026, 9, 8);
    private static final int CHUNK = 2000;

    private final JdbcTemplate jdbc;

    public DemoDataSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public synchronized SeedStats seedIfAbsent() {
        SeedStats stats = new SeedStats();
        Long existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_well_group WHERE group_code LIKE 'SEED-WG-%'", Long.class);
        if (existing != null && existing > 0) {
            stats.skipped = true;
            return stats;
        }

        // ---------- 井组 / 试验段 / 监测点 ----------
        List<Long> groupIds = new ArrayList<>();
        List<Long> sectionIds = new ArrayList<>();
        List<Long> pointIds = new ArrayList<>();
        for (int i = 1; i <= GROUP_COUNT; i++) {
            long tenantId = (i <= TENANT_GROUPS) ? 1L : 2L;
            String gCode = seedCode("WG", i);
            long groupId = insert(
                    "INSERT INTO t_well_group (tenant_id, group_code, group_name, location, status, version) "
                            + "VALUES (?,?,?,?, 'ACTIVE', 0)",
                    tenantId, gCode, "压测井组-" + i, i <= TENANT_GROUPS ? "关中片区" : "河西片区");
            groupIds.add(groupId);

            String sCode = seedCode("TS", i);
            long sectionId = insert(
                    "INSERT INTO t_test_section (tenant_id, well_group_id, section_code, section_name, "
                            + "interval_top_m, interval_bottom_m, status, version) VALUES (?,?,?,?,?,?, 'ACTIVE', 0)",
                    tenantId, groupId, sCode, "压测试验段-" + i,
                    new BigDecimal("1000.00"), new BigDecimal("1800.00"));
            sectionIds.add(sectionId);

            String pCode = seedCode("MP", i);
            long pointId = insert(
                    "INSERT INTO t_monitor_point (tenant_id, test_section_id, point_code, point_name, "
                            + "well_name, point_type, status, version) VALUES (?,?,?,?,?, 'WELLHEAD', 'ACTIVE', 0)",
                    tenantId, sectionId, pCode, "压测监测点-" + i, "DR-" + pad(i));
            pointIds.add(pointId);
            stats.groups++;
            stats.sections++;
            stats.points++;
        }

        // ---------- 班次 / 批次（10 个业务日期） ----------
        List<Long> batchIds = new ArrayList<>();
        String[] statusCycle = {"DRAFT", "RECORDED", "ACCEPTED", "ACCOUNTED"};
        for (int d = 0; d < BIZ_DATES; d++) {
            LocalDate date = BIZ_BASE.plusDays(d);
            for (int i = 0; i < GROUP_COUNT; i++) {
                long tenantId = i < TENANT_GROUPS ? 1L : 2L;
                long groupId = groupIds.get(i);
                long sectionId = sectionIds.get(i);
                long pointId = pointIds.get(i);
                String shiftCode = seedCode("WG", i + 1) + "-" + seedCode("TS", i + 1) + "-" + date + "-S1";
                long shiftId = insert(
                        "INSERT INTO t_reinjection_shift (tenant_id, test_section_id, shift_date, shift_index, "
                                + "shift_code, operator_name, planned_injection_m3h, status, version) "
                                + "VALUES (?,?,?,1,?,?,?, 'CLOSED', 0)",
                        tenantId, sectionId, date, shiftCode, "值班员-" + (i + 1), new BigDecimal("80.000"));
                stats.shifts++;

                String batchNo = shiftCode + "-" + seedCode("MP", i + 1);
                String status = statusCycle[(d + i) % statusCycle.length];
                long batchId = insert(
                        "INSERT INTO t_monitor_batch (tenant_id, well_group_id, test_section_id, shift_id, "
                                + "monitor_point_id, batch_no, biz_date, wellhead_snapshot, status, "
                                + "accepted_version, version) VALUES (?,?,?,?,?,?,?,?,?, 1, 1)",
                        tenantId, groupId, sectionId, shiftId, pointId, batchNo, date,
                        "{\"seed\":true,\"group\":\"" + seedCode("WG", i + 1) + "\"}", status);
                batchIds.add(batchId);
                stats.batches++;
            }
        }

        // ---------- 100,000 条井口读数事件 ----------
        List<Object[]> readingRows = new ArrayList<>(CHUNK);
        int totalReadings = 0;
        int batchIndex = 0;
        for (int d = 0; d < BIZ_DATES; d++) {
            LocalDate date = BIZ_BASE.plusDays(d);
            for (int i = 0; i < GROUP_COUNT; i++) {
                long batchId = batchIds.get(batchIndex++);
                String groupCode = seedCode("WG", i + 1);
                for (int k = 0; k < READINGS_PER_BATCH; k++) {
                    LocalDateTime ts = date.atStartOfDay().plusMinutes(5L * k); // 5 分钟一条
                    BigDecimal pressure = pressure(i, k);
                    BigDecimal temp = rounded(55 + 4 * Math.sin((i + k) / 12.0));
                    BigDecimal flow = rounded(70 + 8 * Math.cos(k / 20.0) + (i % 5));
                    BigDecimal volume = rounded(flow.doubleValue() * 5.0 / 60.0);
                    String snap = "{\"group\":\"" + groupCode + "\",\"pressureMpa\":" + pressure + "}";
                    readingRows.add(new Object[]{batchId, Timestamp.valueOf(ts), pressure, temp, flow, volume, snap});
                    totalReadings++;
                    if (readingRows.size() == CHUNK) {
                        flushReadings(readingRows);
                        readingRows.clear();
                    }
                }
            }
        }
        if (!readingRows.isEmpty()) {
            flushReadings(readingRows);
        }
        stats.readings = totalReadings;

        // ---------- 150,000 条秒级遥测（3 个日期分区） ----------
        List<Object[]> telemetryRows = new ArrayList<>(CHUNK);
        int totalTelemetry = 0;
        for (int d = 0; d < TELEMETRY_DATES; d++) {
            LocalDate date = TELEMETRY_BASE.plusDays(d);
            for (int i = 0; i < GROUP_COUNT; i++) {
                long tenantId = i < TENANT_GROUPS ? 1L : 2L;
                long groupId = groupIds.get(i);
                long sectionId = sectionIds.get(i);
                long pointId = pointIds.get(i);
                for (int s = 0; s < TELEMETRY_PER_GROUP_DATE; s++) {
                    // 秒级采样、全天均匀抽取（步长 69s，覆盖 00:00-23:57 的各时间桶与分区）
                    LocalDateTime ts = date.atStartOfDay().plusSeconds(s * 69L);
                    BigDecimal pressure = telemetryPressure(i, s);
                    BigDecimal temp = rounded(56 + 3 * Math.sin(s / 300.0));
                    BigDecimal flow = rounded(72 + 6 * Math.cos(s / 500.0) + (i % 4));
                    // partition_date 为生成列，不在插入列中
                    telemetryRows.add(new Object[]{tenantId, groupId, sectionId, pointId,
                            Timestamp.valueOf(ts), pressure, temp, flow});
                    totalTelemetry++;
                    if (telemetryRows.size() == CHUNK) {
                        flushTelemetry(telemetryRows);
                        telemetryRows.clear();
                    }
                }
            }
        }
        if (!telemetryRows.isEmpty()) {
            flushTelemetry(telemetryRows);
        }
        stats.telemetry = totalTelemetry;

        // ---------- t1view 对象授权：租户 1 的前 10 个井组 ----------
        for (int i = 0; i < 10; i++) {
            jdbc.update("INSERT INTO t_object_grant (tenant_id, grantee_account, object_type, object_id) "
                    + "VALUES (1, ?, 'WELL_GROUP', ?)", VIEWER_ACCOUNT, groupIds.get(i));
            stats.grants++;
        }
        return stats;
    }

    private void flushReadings(List<Object[]> rows) {
        jdbc.batchUpdate(
                "INSERT INTO t_wellhead_reading (batch_id, reading_time_local, pressure_mpa, temperature_c, "
                        + "flow_m3h, injection_volume_m3, wellhead_snapshot, version) "
                        + "VALUES (?,?,?,?,?,?,?,0)", rows);
    }

    private void flushTelemetry(List<Object[]> rows) {
        jdbc.batchUpdate(
                "INSERT INTO t_wellhead_telemetry (tenant_id, well_group_id, test_section_id, monitor_point_id, "
                        + "reading_time, pressure_mpa, temperature_c, flow_m3h) VALUES (?,?,?,?,?,?,?,?)", rows);
    }

    private long insert(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            // 显式只取 ID，避免 H2 把 CREATE_TIME/UPDATE_TIME 也作为生成列返回多键
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"ID"});
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("无法获取自增主键");
        }
        return key.longValue();
    }

    /** 压力落在约 0.20-0.90 MPa，跨批次有差异，支撑区间/组合检索。 */
    private static BigDecimal pressure(int groupIndex, int readingIndex) {
        double v = 0.55 + 0.25 * Math.sin(readingIndex / 18.0) + 0.05 * Math.sin(groupIndex / 3.0);
        return rounded(Math.max(0.15, Math.min(0.95, v)));
    }

    private static BigDecimal telemetryPressure(int groupIndex, int secondIndex) {
        double v = 0.60 + 0.20 * Math.sin(secondIndex / 240.0) + 0.03 * Math.sin(groupIndex / 2.0);
        return rounded(Math.max(0.20, Math.min(0.98, v)));
    }

    private static BigDecimal rounded(double v) {
        return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP);
    }

    private static String pad(int i) {
        return i < 10 ? "0" + i : String.valueOf(i);
    }

    private static String seedCode(String prefix, int i) {
        return "SEED-" + prefix + "-" + pad(i);
    }
}
