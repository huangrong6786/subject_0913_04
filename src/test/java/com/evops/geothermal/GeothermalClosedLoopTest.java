package com.evops.geothermal;

import com.evops.common.BizException;
import com.evops.common.RequestContext;
import com.evops.geothermal.dto.BatchCreateRequest;
import com.evops.geothermal.dto.BatchDetailView;
import com.evops.geothermal.dto.MonitorPointRequest;
import com.evops.geothermal.dto.ReadingSubmitRequest;
import com.evops.geothermal.dto.ShiftRequest;
import com.evops.geothermal.dto.TestSectionRequest;
import com.evops.geothermal.dto.WellGroupRequest;
import com.evops.geothermal.entity.BizWriteAudit;
import com.evops.geothermal.entity.MonitorBatch;
import com.evops.geothermal.entity.MonitorPoint;
import com.evops.geothermal.entity.ReinjectionShift;
import com.evops.geothermal.entity.TestSection;
import com.evops.geothermal.entity.WellGroup;
import com.evops.geothermal.entity.WellheadReading;
import com.evops.geothermal.enums.BatchStatus;
import com.evops.geothermal.mapper.BizWriteAuditMapper;
import com.evops.geothermal.mapper.WellheadReadingMapper;
import com.evops.geothermal.service.GeothermalService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 地热井回灌闭环集成测试。
 * 困难级约束覆盖：
 *  - 业务对象：井组、试验段、监测点、班次、批次、读数、审计（>= 2）
 *  - 业务日期：3 个班次/批次业务日期
 *  - 5 个并发写入请求：同批次乐观锁竞争 + 同请求号幂等竞争
 *  - 跨表写入同事务 + 请求号/操作者/业务时区/版本快照审计
 */
@SpringBootTest
@ActiveProfiles("test")
class GeothermalClosedLoopTest {

    @Autowired
    private GeothermalService service;
    @Autowired
    private WellheadReadingMapper readingMapper;
    @Autowired
    private BizWriteAuditMapper auditMapper;

    private static final LocalDate D1 = LocalDate.of(2026, 9, 10);
    private static final LocalDate D2 = LocalDate.of(2026, 9, 11);
    private static final LocalDate D3 = LocalDate.of(2026, 9, 12);

    // ---------- helpers ----------

    private void ctx(String requestNo) {
        RequestContext.set(new RequestContext.Ctx(requestNo, 1001L, "操作员甲", "Asia/Shanghai"));
    }

    private String reqNo(String tag) {
        return tag + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private WellGroup createGroup(String code) {
        ctx(reqNo("g-" + code));
        WellGroupRequest req = new WellGroupRequest();
        req.setGroupCode(code);
        req.setGroupName("井组-" + code);
        req.setLocation("咸阳");
        return service.createGroup(req);
    }

    private TestSection createSection(Long groupId, String code) {
        ctx(reqNo("s-" + code));
        TestSectionRequest req = new TestSectionRequest();
        req.setWellGroupId(groupId);
        req.setSectionCode(code);
        req.setSectionName("试验段-" + code);
        req.setIntervalTopM(new BigDecimal("1200.00"));
        req.setIntervalBottomM(new BigDecimal("1600.00"));
        return service.createSection(req);
    }

    private MonitorPoint createPoint(Long sectionId, String code, String wellName) {
        ctx(reqNo("p-" + code));
        MonitorPointRequest req = new MonitorPointRequest();
        req.setTestSectionId(sectionId);
        req.setPointCode(code);
        req.setPointName("井口-" + code);
        req.setWellName(wellName);
        return service.createPoint(req);
    }

    private ReinjectionShift createShift(Long sectionId, LocalDate date, int index) {
        ctx(reqNo("sh-" + date + "-" + index));
        ShiftRequest req = new ShiftRequest();
        req.setTestSectionId(sectionId);
        req.setShiftDate(date);
        req.setShiftIndex(index);
        req.setOperatorName("值班员");
        req.setPlannedInjectionM3h(new BigDecimal("80.000"));
        return service.createShift(req);
    }

    private MonitorBatch createBatch(Long shiftId, Long pointId, LocalDate date) {
        ctx(reqNo("b-" + date));
        BatchCreateRequest req = new BatchCreateRequest();
        req.setShiftId(shiftId);
        req.setMonitorPointId(pointId);
        req.setBizDate(date);
        return service.createBatch(req);
    }

    private ReadingSubmitRequest readings(String time, String p, String t, String f, String v) {
        ReadingSubmitRequest.ReadingItem item = new ReadingSubmitRequest.ReadingItem();
        item.setReadingTimeLocal(time);
        item.setPressureMpa(new BigDecimal(p));
        item.setTemperatureC(new BigDecimal(t));
        item.setFlowM3h(new BigDecimal(f));
        item.setInjectionVolumeM3(new BigDecimal(v));
        ReadingSubmitRequest req = new ReadingSubmitRequest();
        req.setReadings(Collections.singletonList(item));
        return req;
    }

    // ---------- 测试 ----------

    /** 端到端闭环：建档 -> 建批 -> 压力/温度/流量/回灌量原子落库 -> 验收 -> 落账 -> 删除保护 */
    @Test
    void fullLifecycle_atomicReadings_snapshot_acceptAccount_deleteProtected() {
        WellGroup group = createGroup("WG-A");
        TestSection section = createSection(group.getId(), "TS-A1");
        MonitorPoint point = createPoint(section.getId(), "MP-A1", "DR-01 地热井");
        ReinjectionShift shift = createShift(section.getId(), D1, 1);
        MonitorBatch batch = createBatch(shift.getId(), point.getId(), D1);

        // 一次请求两条读数：压力/温度/流量/回灌量齐全
        ctx(reqNo("r"));
        ReadingSubmitRequest.ReadingItem i1 = new ReadingSubmitRequest.ReadingItem();
        i1.setReadingTimeLocal("2026-09-10T08:00:00");
        i1.setPressureMpa(new BigDecimal("0.620"));
        i1.setTemperatureC(new BigDecimal("58.400"));
        i1.setFlowM3h(new BigDecimal("76.500"));
        i1.setInjectionVolumeM3(new BigDecimal("612.000"));
        ReadingSubmitRequest.ReadingItem i2 = new ReadingSubmitRequest.ReadingItem();
        i2.setReadingTimeLocal("2026-09-10T12:00:00");
        i2.setPressureMpa(new BigDecimal("0.635"));
        i2.setTemperatureC(new BigDecimal("58.900"));
        i2.setFlowM3h(new BigDecimal("77.100"));
        i2.setInjectionVolumeM3(new BigDecimal("308.400"));
        ReadingSubmitRequest submit = new ReadingSubmitRequest();
        submit.setReadings(Arrays.asList(i1, i2));
        MonitorBatch recorded = service.submitReadings(batch.getId(), submit);

        assertEquals(BatchStatus.RECORDED.name(), recorded.getStatus());
        List<WellheadReading> rows = readingMapper.selectList(
                new QueryWrapper<WellheadReading>().eq("batch_id", batch.getId()));
        assertEquals(2, rows.size());
        // 井口快照保存四要素与复合键上下文
        assertTrue(rows.get(0).getWellheadSnapshot().contains("pressureMpa"));
        assertTrue(rows.get(0).getWellheadSnapshot().contains("injectionVolumeM3"));
        assertTrue(rows.get(0).getWellheadSnapshot().contains("WG-A"));
        assertTrue(rows.get(0).getWellheadSnapshot().contains("Asia/Shanghai"));
        assertEquals(8, rows.get(0).getReadingTimeLocal().getHour());

        // 关联查询（复合键视图）
        BatchDetailView view = service.getBatchDetailByCompositeKey("WG-A", "TS-A1", D1, 1, "MP-A1", D1);
        assertEquals(batch.getId(), view.getBatchId());
        assertEquals("WG-A", view.getGroupCode());
        assertEquals("TS-A1", view.getSectionCode());
        assertEquals(2, view.getReadings().size());
        assertEquals(new BigDecimal("0.620"), view.getReadings().get(0).getPressureMpa());

        // 验收：冻结版本快照
        ctx(reqNo("acc"));
        MonitorBatch accepted = service.acceptBatch(batch.getId());
        assertEquals(BatchStatus.ACCEPTED.name(), accepted.getStatus());
        assertNotNull(accepted.getAcceptedVersion());

        // 已验收不能直接删除
        ctx(reqNo("del1"));
        BizException ex1 = assertThrows(BizException.class, () -> service.deleteBatch(batch.getId()));
        assertTrue(ex1.getMessage().contains("不能直接删除"));
        // 已验收不能再写读数
        ctx(reqNo("r2"));
        assertThrows(BizException.class, () -> service.submitReadings(batch.getId(),
                readings("2026-09-10T13:00:00", "0.6", "58", "76", "10")));

        // 落账后同样受删除保护，且不能重复落账/回退
        ctx(reqNo("acct"));
        MonitorBatch accounted = service.accountBatch(batch.getId(), 90001L);
        assertEquals(BatchStatus.ACCOUNTED.name(), accounted.getStatus());
        assertEquals(90001L, accounted.getAccountingId());
        ctx(reqNo("del2"));
        assertThrows(BizException.class, () -> service.deleteBatch(batch.getId()));
        ctx(reqNo("acct2"));
        assertThrows(BizException.class, () -> service.accountBatch(batch.getId(), 90002L));

        // 审计：请求号、操作者、业务时区、版本快照齐全
        List<BizWriteAudit> audits = auditMapper.selectList(
                new QueryWrapper<BizWriteAudit>().eq("object_id", batch.getId())
                        .in("action", Arrays.asList("CREATE", "SUBMIT_READINGS", "ACCEPT", "ACCOUNT"))
                        .orderByAsc("id"));
        assertTrue(audits.size() >= 4);
        BizWriteAudit accAudit = audits.stream().filter(a -> "ACCEPT".equals(a.getAction())).findFirst().get();
        assertEquals(Long.valueOf(1001L), accAudit.getOperatorId());
        assertEquals("操作员甲", accAudit.getOperatorName());
        assertEquals("Asia/Shanghai", accAudit.getBizTimezone());
        assertTrue(accAudit.getVersionSnapshot().contains("acceptedVersion"));
        assertTrue(accAudit.getRequestNo().startsWith("acc-"));
    }

    /** 草稿批次可以删除（连带读数），删除保护只针对已验收/已落账 */
    @Test
    void draftBatch_canDelete_withReadings() {
        WellGroup g = createGroup("WG-D");
        TestSection s = createSection(g.getId(), "TS-D1");
        MonitorPoint p = createPoint(s.getId(), "MP-D1", "DR-02");
        ReinjectionShift sh = createShift(s.getId(), D2, 2);
        MonitorBatch b = createBatch(sh.getId(), p.getId(), D2);
        ctx(reqNo("rr"));
        service.submitReadings(b.getId(), readings("2026-09-11T09:00:00", "0.5", "55", "70", "120"));
        ctx(reqNo("del"));
        service.deleteBatch(b.getId());
        assertEquals(0, readingMapper.selectCount(new QueryWrapper<WellheadReading>().eq("batch_id", b.getId())));
    }

    /** 关键业务键唯一：井组/试验段/监测点/班次/批次 */
    @Test
    void businessKeys_mustBeUnique() {
        WellGroup g = createGroup("WG-U");
        assertThrows(BizException.class, () -> createGroup("WG-U"));

        TestSection s = createSection(g.getId(), "TS-U1");
        assertThrows(BizException.class, () -> createSection(g.getId(), "TS-U1"));

        MonitorPoint p = createPoint(s.getId(), "MP-U1", "DR-03");
        assertThrows(BizException.class, () -> createPoint(s.getId(), "MP-U1", "DR-03"));

        createShift(s.getId(), D1, 1);
        assertThrows(BizException.class, () -> createShift(s.getId(), D1, 1)); // 同复合键
        assertThrows(BizException.class, () -> createShift(s.getId(), D1, 1)); // 同编码

        ReinjectionShift sh = createShift(s.getId(), D2, 1);
        createBatch(sh.getId(), p.getId(), D2);
        assertThrows(BizException.class, () -> createBatch(sh.getId(), p.getId(), D2));
    }

    /** 复合键建批（井组+试验段+业务日期+班次序号+监测点），班次不存在时同事务隐式建班；覆盖 3 个业务日期 */
    @Test
    void compositeKeyCreate_andThreeBizDates() {
        WellGroup g = createGroup("WG-C");
        TestSection s = createSection(g.getId(), "TS-C1");
        createPoint(s.getId(), "MP-C1", "DR-04");

        for (LocalDate d : Arrays.asList(D1, D2, D3)) {
            ctx(reqNo("bk-" + d));
            BatchCreateRequest req = new BatchCreateRequest();
            req.setGroupCode("WG-C");
            req.setSectionCode("TS-C1");
            req.setPointCode("MP-C1");
            req.setBizDate(d);
            req.setShiftIndex(1);
            MonitorBatch b = service.createBatch(req);
            assertNotNull(b.getId());
            assertTrue(b.getBatchNo().contains(String.valueOf(d)));
        }
        // 三个业务日期的班次都可查到
        assertEquals(1, service.listShifts(s.getId(), D1).size());
        assertEquals(1, service.listShifts(s.getId(), D2).size());
        assertEquals(1, service.listShifts(s.getId(), D3).size());
        // 批次快照含 3 层复合键
        BatchDetailView v = service.getBatchDetailByCompositeKey("WG-C", "TS-C1", D3, 1, "MP-C1", D3);
        assertEquals(D3, v.getBizDate());
        assertTrue(v.getBatchNo().startsWith("WG-C-TS-C1-2026-09-12-S1-MP-C1"));
    }

    /** 同请求号不能处理两次（幂等闸门） */
    @Test
    void duplicateRequestNo_rejected() {
        WellGroup g = createGroup("WG-R");
        ctx("FIXED-REQ-001");
        WellGroupRequest req = new WellGroupRequest();
        req.setGroupCode("WG-R1");
        req.setGroupName("一");
        service.createGroup(req);

        ctx("FIXED-REQ-001");
        WellGroupRequest req2 = new WellGroupRequest();
        req2.setGroupCode("WG-R2");
        req2.setGroupName("二");
        BizException ex = assertThrows(BizException.class, () -> service.createGroup(req2));
        assertTrue(ex.getMessage().contains("请求号已处理"));
        // 第二个井组未建立
        assertEquals(0, service.listGroups().stream().filter(x -> "WG-R2".equals(x.getGroupCode())).count());
    }

    /** 读数原子落库：一条非法数据导致整批回滚，无任何读数残留 */
    @Test
    void readings_atomicRollback() {
        WellGroup g = createGroup("WG-T");
        TestSection s = createSection(g.getId(), "TS-T1");
        MonitorPoint p = createPoint(s.getId(), "MP-T1", "DR-05");
        ReinjectionShift sh = createShift(s.getId(), D3, 3);
        MonitorBatch b = createBatch(sh.getId(), p.getId(), D3);
        long before = readingMapper.selectCount(new QueryWrapper<>());

        ctx(reqNo("bad"));
        ReadingSubmitRequest.ReadingItem bad = new ReadingSubmitRequest.ReadingItem();
        bad.setReadingTimeLocal("2026-09-12T10:00:00");
        bad.setPressureMpa(null); // NOT NULL 违约
        bad.setTemperatureC(new BigDecimal("58"));
        bad.setFlowM3h(new BigDecimal("70"));
        bad.setInjectionVolumeM3(new BigDecimal("100"));
        ReadingSubmitRequest req = new ReadingSubmitRequest();
        req.setReadings(Collections.singletonList(bad));
        assertThrows(Exception.class, () -> service.submitReadings(b.getId(), req));

        assertEquals(before, readingMapper.selectCount(new QueryWrapper<>()));
        MonitorBatch reloaded = service.listBatches(null).stream()
                .filter(x -> x.getId().equals(b.getId())).findFirst().get();
        assertEquals(BatchStatus.DRAFT.name(), reloaded.getStatus()); // 状态推进一并回滚
    }

    /**
     * 5 个并发写入请求打到同一批次：乐观锁保证只有 1 个请求完成 DRAFT->RECORDED，
     * 其余 4 个事务整体回滚，最终只保留成功请求的 1 条读数。
     */
    @Test
    void fiveConcurrentWrites_sameBatch_onlyOneSucceeds() throws Exception {
        WellGroup g = createGroup("WG-X");
        TestSection s = createSection(g.getId(), "TS-X1");
        MonitorPoint p = createPoint(s.getId(), "MP-X1", "DR-06");
        ReinjectionShift sh = createShift(s.getId(), D1, 5);
        MonitorBatch b = createBatch(sh.getId(), p.getId(), D1);

        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    ctx("CONC-SAME-" + idx + "-" + UUID.randomUUID().toString().substring(0, 6));
                    start.await();
                    service.submitReadings(b.getId(),
                            readings("2026-09-10T" + String.format("%02d", idx + 1) + ":00:00",
                                    "0.6" + idx, "5" + idx + ".0", "7" + idx + ".0", String.valueOf(100 + idx)));
                    success.incrementAndGet();
                } catch (Throwable e) {
                    errors.add(e);
                    conflict.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, success.get(), "只能有一个并发请求成功");
        assertEquals(4, conflict.get());
        List<WellheadReading> rows = readingMapper.selectList(
                new QueryWrapper<WellheadReading>().eq("batch_id", b.getId()));
        assertEquals(1, rows.size(), "失败事务的读数必须随事务回滚");
        MonitorBatch finalBatch = service.listBatches(null).stream()
                .filter(x -> x.getId().equals(b.getId())).findFirst().get();
        assertEquals(BatchStatus.RECORDED.name(), finalBatch.getStatus());
    }

    /**
     * 5 个并发写入请求使用同一请求号：审计表唯一索引兜底，只有 1 个请求落账，审计只 1 条，
     * 其余 4 个事务整体回滚（井组记录也不会留下）。
     */
    @Test
    void fiveConcurrentWrites_sameRequestNo_onlyOnePersists() throws Exception {
        WellGroup g = createGroup("WG-Y");
        TestSection s = createSection(g.getId(), "TS-Y1");
        createPoint(s.getId(), "MP-Y1", "DR-07");

        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        String sameRequestNo = "RACE-" + UUID.randomUUID().toString().substring(0, 8);

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    RequestContext.set(new RequestContext.Ctx(sameRequestNo, 3003L, "操作员丙", "Asia/Shanghai"));
                    start.await();
                    WellGroupRequest req = new WellGroupRequest();
                    req.setGroupCode("WG-Z" + idx + "-" + sameRequestNo.substring(5));
                    req.setGroupName("竞态井组" + idx);
                    service.createGroup(req);
                    success.incrementAndGet();
                } catch (Throwable e) {
                    rejected.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, success.get(), "同一请求号的 5 个并发请求只能有 1 个落账");
        assertEquals(4, rejected.get());
        Long auditCount = auditMapper.selectCount(
                new QueryWrapper<BizWriteAudit>().eq("request_no", sameRequestNo));
        assertEquals(Long.valueOf(1L), auditCount, "审计表中同请求号只能有一条记录");
        // 失败事务回滚：井组总数只能多出成功的那 1 个
        long persisted = service.listGroups().stream()
                .filter(x -> x.getGroupCode().endsWith("-" + sameRequestNo.substring(5)))
                .count();
        assertEquals(1, persisted);
    }

    /** 班次状态流转 OPEN -> CLOSED，重复闭班被拒绝 */
    @Test
    void shiftStatusFlow_openToClose() {
        WellGroup g = createGroup("WG-S");
        TestSection s = createSection(g.getId(), "TS-S1");
        ReinjectionShift sh = createShift(s.getId(), D2, 7);
        ctx(reqNo("close"));
        assertEquals("CLOSED", service.closeShift(sh.getId()).getStatus());
        ctx(reqNo("close2"));
        assertThrows(BizException.class, () -> service.closeShift(sh.getId()));
    }
}
