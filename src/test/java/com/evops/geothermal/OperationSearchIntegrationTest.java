package com.evops.geothermal;

import com.evops.common.BizException;
import com.evops.geothermal.bootstrap.DemoDataSeeder;
import com.evops.geothermal.bootstrap.SeedStats;
import com.evops.geothermal.dto.OperationBatchQuery;
import com.evops.geothermal.dto.OperationBatchRow;
import com.evops.geothermal.dto.PageResult;
import com.evops.geothermal.dto.TelemetryAggregateQuery;
import com.evops.geothermal.dto.TelemetryAggregateResult;
import com.evops.geothermal.dto.WellGroupPressureRow;
import com.evops.geothermal.security.AccountPrincipal;
import com.evops.geothermal.security.TenantRole;
import com.evops.geothermal.service.OperationQueryService;
import com.evops.geothermal.service.TelemetryQueryService;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 运营检索 / 多租户数据权限 / 秒级遥测聚合的大规模集成测试。
 * 基于 40 井组、400 批次、100000 读数事件、150000 秒级遥测验证：
 *  - >=4 条件 AND/范围组合；
 *  - 100k 量级确定性稳定排序，游标与页码分页不重不漏、主表不被一对多放大；
 *  - pageSize 仅 1-100；
 *  - 每次查询都施加租户与角色数据权限（admin 全租户 / viewer 仅授权井组）；
 *  - 遥测聚合强制分区裁剪；40 井组压力汇总为单集合查询（无逐井 N+1）。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperationSearchIntegrationTest {

    private static final LocalDate BIZ_FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate BIZ_TO = LocalDate.of(2026, 9, 10);
    // 既有闭环测试会在 09-10~09-12 造租户 1 数据；全租户遍历统一收敛到 09-01~09-09 以隔离 SEED 集
    private static final LocalDate SEED_SAFE_FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate SEED_SAFE_TO = LocalDate.of(2026, 9, 9);
    private static final LocalDate TELEM_D1 = LocalDate.of(2026, 9, 8);
    private static final LocalDate TELEM_D3 = LocalDate.of(2026, 9, 10);

    @Autowired private DemoDataSeeder seeder;
    @Autowired private OperationQueryService queryService;
    @Autowired private TelemetryQueryService telemetryService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SecurityManager securityManager;

    private Long tenant1Group1;
    private Long tenant1Section1;

    @BeforeAll
    void seed() {
        SeedStats stats = seeder.seedIfAbsent();
        if (!stats.skipped) {
            assertEquals(40, stats.groups);
            assertEquals(400, stats.batches);
            assertEquals(100_000, stats.readings);
            assertEquals(150_000, stats.telemetry);
            assertEquals(10, stats.grants);
        }
        tenant1Group1 = jdbc.queryForObject(
                "SELECT id FROM t_well_group WHERE group_code='SEED-WG-01'", Long.class);
        tenant1Section1 = jdbc.queryForObject(
                "SELECT id FROM t_test_section WHERE section_code='SEED-TS-01'", Long.class);
    }

    // ---------- 数据规模 ----------

    @Test
    void seeded_expectedVolumes() {
        assertEquals(40L, count("SELECT COUNT(*) FROM t_well_group WHERE group_code LIKE 'SEED-WG-%'"));
        assertEquals(20L, count("SELECT COUNT(*) FROM t_well_group WHERE tenant_id=1 AND group_code LIKE 'SEED-WG-%'"));
        assertEquals(20L, count("SELECT COUNT(*) FROM t_well_group WHERE tenant_id=2 AND group_code LIKE 'SEED-WG-%'"));
        assertEquals(400L, count("SELECT COUNT(*) FROM t_monitor_batch WHERE batch_no LIKE 'SEED-%'"));
        assertEquals(100_000L, count(
                "SELECT COUNT(*) FROM t_wellhead_reading r JOIN t_monitor_batch b ON r.batch_id=b.id "
                        + "WHERE b.batch_no LIKE 'SEED-%'"));
        assertEquals(150_000L, count("SELECT COUNT(*) FROM t_wellhead_telemetry"));
        // 生成列分区键与采样日期一致
        assertEquals(150_000L, count(
                "SELECT COUNT(*) FROM t_wellhead_telemetry WHERE partition_date = CAST(reading_time AS DATE)"));
    }

    // ---------- ≥4 条件 AND/范围组合 ----------

    @Test
    void fiveConditions_andCombination_allRowsSatisfyEveryFilter() {
        OperationBatchQuery q = new OperationBatchQuery();
        q.setGroupId(tenant1Group1);                 // 条件1 井组
        q.setSectionId(tenant1Section1);             // 条件2 试验段
        q.setStatus("ACCEPTED");                     // 条件3 状态
        q.setBizDateFrom(BIZ_FROM);                  // 条件4 日期范围
        q.setBizDateTo(BIZ_TO);
        q.setPressureMin(new java.math.BigDecimal("0.50"));  // 条件5 井口压力区间（EXISTS 半连接）
        q.setPageSize(100);

        PageResult<OperationBatchRow> page = runAsAdmin1(() -> queryService.searchBatches(q));
        // 井组0 的 ACCEPTED 出现在 (d+0)%4==2 -> d=2、d=6 两个业务日期
        assertEquals(2, page.getTotal());
        assertEquals(2, page.getRecords().size());
        Set<LocalDate> dates = new HashSet<>();
        for (OperationBatchRow row : page.getRecords()) {
            assertEquals(tenant1Group1, row.getGroupId());
            assertEquals(tenant1Section1, row.getSectionId());
            assertEquals("ACCEPTED", row.getStatus());
            assertFalse(row.getBizDate().isBefore(BIZ_FROM));
            assertFalse(row.getBizDate().isAfter(BIZ_TO));
            assertNotNull(row.getBatchPressureMax());
            assertTrue(row.getBatchPressureMax().compareTo(new java.math.BigDecimal("0.50")) >= 0,
                    "压力 EXISTS 命中必须满足压力区间");
            dates.add(row.getBizDate());
        }
        assertEquals(new HashSet<>(Arrays.asList(LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 7))), dates);
    }
    @Test
    void pressureRange_excludesBatchesOutsideRange() {
        // 全部读数压力约 0.15-0.95；把上限压到极低，应无批次命中（EXISTS 不误放）
        OperationBatchQuery q = new OperationBatchQuery();
        q.setGroupId(tenant1Group1);
        q.setPressureMax(new java.math.BigDecimal("0.01"));
        q.setPageSize(50);
        PageResult<OperationBatchRow> none = runAsAdmin1(() -> queryService.searchBatches(q));
        assertEquals(0, none.getTotal());
        assertTrue(none.getRecords().isEmpty());

        // 很宽的压力区间应命中该井组全部 10 个批次
        OperationBatchQuery wide = new OperationBatchQuery();
        wide.setGroupId(tenant1Group1);
        wide.setPressureMin(new java.math.BigDecimal("0.10"));
        wide.setPressureMax(new java.math.BigDecimal("1.00"));
        wide.setPageSize(50);
        PageResult<OperationBatchRow> all = runAsAdmin1(() -> queryService.searchBatches(wide));
        assertEquals(10, all.getTotal());
    }

    // ---------- 稳定排序 + 确定性分页（100k 事件量级） ----------

    @Test
    void offsetPaging_stableOrder_noDuplicate_noGap_noFanOut() {
        OperationBatchQuery scope = safeWindowQuery();
        List<Long> ids = walkByOffset(13, scope);
        long total = count("SELECT COUNT(*) FROM t_monitor_batch WHERE tenant_id=1 "
                + "AND batch_no LIKE 'SEED-%' AND biz_date BETWEEN DATE '2026-09-01' AND DATE '2026-09-09'");
        assertEquals(total, ids.size());
        assertEquals(total, new HashSet<>(ids).size(), "一对多读数关联不得放大/重复主表");
        assertDeterministicOrder(ids);
    }

    @Test
    void cursorPaging_matchesOffsetSequence_exactly() {
        OperationBatchQuery scope = safeWindowQuery();
        List<Long> byOffset = walkByOffset(7, scope);
        List<Long> byCursor = walkByCursor(7, scope);
        assertEquals(byOffset, byCursor, "游标分页必须与确定性主键分页序列一致");
    }

    @Test
    void paging_underPressureFilter_stillNoFanOut() {
        OperationBatchQuery filter = safeWindowQuery();
        filter.setPressureMin(new java.math.BigDecimal("0.55")); // 触发与读数表 EXISTS 一对多关联
        List<Long> ids = walkByOffset(17, filter);
        long total = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT b.id) FROM t_monitor_batch b "
                        + "WHERE b.tenant_id=1 AND b.batch_no LIKE 'SEED-%' "
                        + "AND b.biz_date BETWEEN DATE '2026-09-01' AND DATE '2026-09-09' AND EXISTS ("
                        + "SELECT 1 FROM t_wellhead_reading r WHERE r.batch_id=b.id AND r.pressure_mpa >= 0.55)",
                Long.class);
        assertEquals(total, ids.size());
        assertEquals(total, new HashSet<>(ids).size(), "EXISTS 半连接不得放大主表");
    }

    @Test
    void pageSize_mustBe1to100() {
        assertThrows(BizException.class, () -> runAsAdmin1(() -> {
            OperationBatchQuery q = new OperationBatchQuery();
            q.setPageSize(0);
            return queryService.searchBatches(q);
        }));
        assertThrows(BizException.class, () -> runAsAdmin1(() -> {
            OperationBatchQuery q = new OperationBatchQuery();
            q.setPageSize(101);
            return queryService.searchBatches(q);
        }));
        OperationBatchQuery ok = new OperationBatchQuery();
        ok.setPageSize(100);
        assertTrue(runAsAdmin1(() -> queryService.searchBatches(ok)).getRecords().size() <= 100);
    }

    // ---------- 多租户 / 角色数据权限 ----------

    @Test
    void tenantIsolation_adminSeesOnlyOwnTenant() {
        long t1 = runAsAdmin1(() -> queryService.searchBatches(safeWindowQuery(100))).getTotal();
        long t2 = runAsAccount("t2admin", 2L, TenantRole.TENANT_ADMIN,
                () -> queryService.searchBatches(baseQuery(100))).getTotal();
        assertEquals(180, t1, "租户1：20 井组 × 9 个安全日期（09-01~09-09）");
        assertEquals(200, t2, "租户2：20 井组 × 10 个业务日期");
        // t2 管理员看不到任何租户 1 批次
        long t2SeeingT1Group = runAsAccount("t2admin", 2L, TenantRole.TENANT_ADMIN, () -> {
            OperationBatchQuery q = baseQuery(100);
            q.setGroupId(tenant1Group1); // 越权指定租户1井组
            return queryService.searchBatches(q);
        }).getTotal();
        assertEquals(0, t2SeeingT1Group);
    }

    @Test
    void viewer_seesOnlyGrantedGroups() {
        PageResult<OperationBatchRow> page = runAsAccount("t1view", 1L, TenantRole.TENANT_VIEWER,
                () -> queryService.searchBatches(safeWindowQuery(100)));
        // 仅被授权的前 10 个井组 × 9 个安全日期（09-01~09-09）
        assertEquals(90, page.getTotal());
        Set<Long> allowedGroupIds = jdbc.queryForList(
                "SELECT object_id FROM t_object_grant WHERE grantee_account='t1view' AND object_type='WELL_GROUP'",
                Long.class).stream().collect(Collectors.toSet());
        assertEquals(10, allowedGroupIds.size());
        for (OperationBatchRow row : page.getRecords()) {
            assertTrue(allowedGroupIds.contains(row.getGroupId()), "只读账号不得看到未授权井组: " + row.getGroupId());
        }
    }

    // ---------- 秒级遥测：分区裁剪 ----------

    @Test
    void telemetryAggregate_partitionPruned_samplesMatchWindow() {
        TelemetryAggregateQuery q = new TelemetryAggregateQuery();
        q.setGroupId(tenant1Group1);
        q.setDateFrom(TELEM_D1);
        q.setDateTo(LocalDate.of(2026, 9, 9)); // 2 个分区
        q.setGranularity("HOUR");
        TelemetryAggregateResult result = runAsAdmin1(() -> telemetryService.aggregate(q));
        assertEquals(TELEM_D1, result.getPartitionDateFrom(), "必须回传实际分区裁剪范围");
        assertEquals(2500L, result.getTotalSamples(), "2 分区 × 1250 秒，必须分区裁剪后精确命中");
        // 每日样本以 69s 步长均匀覆盖全天 24 个小时桶，2 日共 48 行
        assertEquals(48, result.getBuckets().size());
        assertTrue(result.getBuckets().stream().allMatch(b -> b.getSampleCount() > 0));
    }

    @Test
    void telemetryAggregate_requiresPartitionWindow_andCapsRange() {
        // 缺日期范围禁止聚合（杜绝全表）
        assertThrows(BizException.class, () -> runAsAdmin1(() -> telemetryService.aggregate(new TelemetryAggregateQuery())));
        // 超过 7 天上限拒绝
        TelemetryAggregateQuery wide = new TelemetryAggregateQuery();
        wide.setDateFrom(LocalDate.of(2026, 9, 1));
        wide.setDateTo(LocalDate.of(2026, 9, 30));
        assertThrows(BizException.class, () -> runAsAdmin1(() -> telemetryService.aggregate(wide)));
    }

    @Test
    void telemetryAggregate_tenantScoped() {
        // t2 管理员按 t1 井组查不到遥测
        TelemetryAggregateQuery q = new TelemetryAggregateQuery();
        q.setGroupId(tenant1Group1);
        q.setDateFrom(TELEM_D1);
        q.setDateTo(TELEM_D3);
        TelemetryAggregateResult none = runAsAccount("t2admin", 2L, TenantRole.TENANT_ADMIN,
                () -> telemetryService.aggregate(q));
        assertEquals(0L, none.getTotalSamples());
    }

    // ---------- 40 井组压力汇总：单集合 GROUP BY，无 N+1 ----------

    @Test
    void wellGroupPressure_singleSetQuery_allGroupsOneCall() {
        List<WellGroupPressureRow> t1 = runAsAdmin1(() -> telemetryService.wellGroupPressure(TELEM_D1, TELEM_D3, null));
        List<WellGroupPressureRow> t2 = runAsAccount("t2admin", 2L, TenantRole.TENANT_ADMIN,
                () -> telemetryService.wellGroupPressure(TELEM_D1, TELEM_D3, null));
        List<WellGroupPressureRow> viewer = runAsAccount("t1view", 1L, TenantRole.TENANT_VIEWER,
                () -> telemetryService.wellGroupPressure(TELEM_D1, TELEM_D3, null));

        // 一次调用返回该租户/授权范围内全部井组，应用层无需也不应逐井查询
        assertEquals(20, t1.size());
        assertEquals(20, t2.size());
        assertEquals(10, viewer.size());
        for (WellGroupPressureRow row : t1) {
            assertEquals(3750L, row.getSampleCount(), "3 分区 × 1250 秒");
            assertNotNull(row.getPressureMin());
            assertNotNull(row.getPressureMax());
            assertNotNull(row.getLatestPressure());
            assertNotNull(row.getLatestReadingTime());
            assertTrue(row.getPressureMax().compareTo(row.getPressureMin()) >= 0);
        }
        // 单分区裁剪：每井 1250 条
        List<WellGroupPressureRow> oneDay = runAsAdmin1(() -> telemetryService.wellGroupPressure(TELEM_D1, TELEM_D1, null));
        assertEquals(20, oneDay.size());
        assertEquals(1250L, oneDay.get(0).getSampleCount());

        // 超过 31 天上限拒绝，强制裁剪
        assertThrows(BizException.class, () -> runAsAdmin1(() ->
                telemetryService.wellGroupPressure(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 30), null)));
    }

    // ---------- helpers ----------

    private OperationBatchQuery baseQuery(int pageSize) {
        OperationBatchQuery q = new OperationBatchQuery();
        q.setPageSize(pageSize);
        return q;
    }

    /** 租户 1 全量遍历的安全窗口：收敛到 09-01~09-09，避开闭环测试 09-10+ 的干扰数据。 */
    private OperationBatchQuery safeWindowQuery() {
        return safeWindowQuery(20);
    }

    private OperationBatchQuery safeWindowQuery(int pageSize) {
        OperationBatchQuery q = baseQuery(pageSize);
        q.setBizDateFrom(SEED_SAFE_FROM);
        q.setBizDateTo(SEED_SAFE_TO);
        return q;
    }

    private List<Long> walkByOffset(int pageSize, OperationBatchQuery template) {
        List<Long> ids = new ArrayList<>();
        int page = 1;
        while (true) {
            final int p = page;
            OperationBatchQuery q = template == null ? new OperationBatchQuery() : cloneQuery(template);
            q.setPageSize(pageSize);
            q.setPage(p);
            PageResult<OperationBatchRow> result = runAsAdmin1(() -> queryService.searchBatches(q));
            if (result.getRecords().isEmpty()) {
                break;
            }
            result.getRecords().forEach(r -> ids.add(r.getId()));
            if (!result.isHasMore() || ids.size() >= result.getTotal()) {
                break;
            }
            page++;
            if (page > 10000) {
                throw new IllegalStateException("分页未终止");
            }
        }
        return ids;
    }

    private List<Long> walkByCursor(int pageSize, OperationBatchQuery template) {
        List<Long> ids = new ArrayList<>();
        String cursor = null;
        while (true) {
            OperationBatchQuery q = template == null ? new OperationBatchQuery() : cloneQuery(template);
            q.setPageSize(pageSize);
            q.setCursor(cursor);
            PageResult<OperationBatchRow> result = runAsAdmin1(() -> queryService.searchBatches(q));
            result.getRecords().forEach(r -> ids.add(r.getId()));
            if (result.getNextCursor() == null) {
                break;
            }
            cursor = result.getNextCursor();
        }
        return ids;
    }

    private OperationBatchQuery cloneQuery(OperationBatchQuery src) {
        OperationBatchQuery q = new OperationBatchQuery();
        q.setGroupId(src.getGroupId());
        q.setSectionId(src.getSectionId());
        q.setStatus(src.getStatus());
        q.setBizDateFrom(src.getBizDateFrom());
        q.setBizDateTo(src.getBizDateTo());
        q.setPressureMin(src.getPressureMin());
        q.setPressureMax(src.getPressureMax());
        return q;
    }

    private void assertDeterministicOrder(List<Long> ids) {
        // 取安全窗口全量行校验 biz_date DESC, id ASC
        List<OperationBatchRow> all = new ArrayList<>();
        int page = 1;
        while (true) {
            final int p = page;
            PageResult<OperationBatchRow> r = runAsAdmin1(() -> {
                OperationBatchQuery q = safeWindowQuery(100);
                q.setPage(p);
                return queryService.searchBatches(q);
            });
            all.addAll(r.getRecords());
            if (!r.isHasMore() || all.size() >= r.getTotal()) {
                break;
            }
            page++;
        }
        for (int i = 1; i < all.size(); i++) {
            OperationBatchRow prev = all.get(i - 1);
            OperationBatchRow cur = all.get(i);
            boolean correct = prev.getBizDate().isAfter(cur.getBizDate())
                    || (prev.getBizDate().equals(cur.getBizDate()) && prev.getId() < cur.getId());
            assertTrue(correct, "排序必须为 biz_date DESC, id ASC（确定性）");
        }
        assertEquals(ids.size(), all.size());
    }

    private long count(String sql) {
        Long v = jdbc.queryForObject(sql, Long.class);
        return v == null ? 0 : v;
    }

    private <T> T runAsAdmin1(java.util.concurrent.Callable<T> action) {
        return runAsAccount("t1admin", 1L, TenantRole.TENANT_ADMIN, action);
    }

    private <T> T runAsAccount(String account, long tenantId, TenantRole role,
                               java.util.concurrent.Callable<T> action) {
        AccountPrincipal principal = new AccountPrincipal(account, account, tenantId,
                tenantId == 1L ? "T1" : "T2", EnumSet.of(role));
        Subject subject = new Subject.Builder(securityManager)
                .authenticated(true)
                .principals(new SimplePrincipalCollection(principal, "evopsRealm"))
                .buildSubject();
        try {
            return subject.execute(() -> {
                try {
                    return action.call();
                } catch (RuntimeException re) {
                    throw re;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (org.apache.shiro.subject.ExecutionException ee) {
            // subject.execute 把可抛异常包成 ExecutionException，解包以对原始 BizException 断言
            Throwable cause = ee.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException(cause);
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException(e);
        }
    }
}
