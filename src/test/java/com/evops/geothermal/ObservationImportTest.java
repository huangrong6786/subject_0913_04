package com.evops.geothermal;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.RequestContext;
import com.evops.geothermal.dto.BatchCreateRequest;
import com.evops.geothermal.dto.ImportSummaryView;
import com.evops.geothermal.dto.MonitorPointRequest;
import com.evops.geothermal.dto.PageResult;
import com.evops.geothermal.dto.ImportRowView;
import com.evops.geothermal.dto.ReadingSubmitRequest;
import com.evops.geothermal.dto.ShiftRequest;
import com.evops.geothermal.dto.TestSectionRequest;
import com.evops.geothermal.dto.WellGroupRequest;
import com.evops.geothermal.entity.BizWriteAudit;
import com.evops.geothermal.entity.ImportFile;
import com.evops.geothermal.entity.ImportShard;
import com.evops.geothermal.entity.MonitorBatch;
import com.evops.geothermal.entity.MonitorPoint;
import com.evops.geothermal.entity.ReinjectionShift;
import com.evops.geothermal.entity.TestSection;
import com.evops.geothermal.entity.WellGroup;
import com.evops.geothermal.entity.WellheadObservation;
import com.evops.geothermal.enums.ImportFileStatus;
import com.evops.geothermal.enums.ImportShardStatus;
import com.evops.geothermal.mapper.BizWriteAuditMapper;
import com.evops.geothermal.mapper.ImportFileMapper;
import com.evops.geothermal.mapper.ImportRowMapper;
import com.evops.geothermal.mapper.ImportShardMapper;
import com.evops.geothermal.mapper.WellheadObservationMapper;
import com.evops.geothermal.service.DeviceQuarantineService;
import com.evops.geothermal.service.GeothermalService;
import com.evops.geothermal.service.ObservationImportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
 * 观测数据 CSV 批量导入集成测试。
 * 覆盖困难级约束：
 *  - 合法/重复/缺列/坏数值混合行逐行隔离（单行失败不回滚整批）
 *  - 文件校验和 + 业务键双重幂等（网关重发同序列号、跨文件重复）
 *  - 已验收(ACCEPTED)/已锁定(班次 CLOSED)数据禁止覆盖
 *  - 失败分片重试 / 断点续传（原始行号、字段、原值、原因保留）
 *  - 坏传感器隔离、跨天补报（biz_date 按观测日派生）
 *  - 50,000 行分片处理（shardSize=1000，>= 50 分片）
 */
@SpringBootTest
@ActiveProfiles("test")
class ObservationImportTest {

    @Autowired
    private ObservationImportService importService;
    @Autowired
    private DeviceQuarantineService quarantineService;
    @Autowired
    private GeothermalService geothermalService;
    @Autowired
    private WellheadObservationMapper observationMapper;
    @Autowired
    private ImportFileMapper fileMapper;
    @Autowired
    private ImportShardMapper shardMapper;
    @Autowired
    private ImportRowMapper rowMapper;
    @Autowired
    private BizWriteAuditMapper auditMapper;

    private static final String HEADER =
            "objectCode,observedAt,pressureMpa,temperatureC,flowM3h,injectionVolumeM3,sourceDevice,serialNo";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ---------- helpers ----------

    private void ctx(String requestNo) {
        RequestContext.set(new RequestContext.Ctx(requestNo, 1001L, "导入测试员", "Asia/Shanghai"));
    }

    private String reqNo(String tag) {
        return tag + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** 建 井组->试验段->监测点 链路，返回监测点编码。 */
    private String createPointChain(String tag) {
        String suffix = uid();
        ctx(reqNo("g"));
        WellGroupRequest g = new WellGroupRequest();
        g.setGroupCode("WG-IMP-" + suffix);
        g.setGroupName("导入井组-" + tag);
        WellGroup group = geothermalService.createGroup(g);

        ctx(reqNo("s"));
        TestSectionRequest s = new TestSectionRequest();
        s.setWellGroupId(group.getId());
        s.setSectionCode("SEC-IMP-" + suffix);
        s.setSectionName("导入试验段-" + tag);
        TestSection section = geothermalService.createSection(s);

        ctx(reqNo("p"));
        MonitorPointRequest p = new MonitorPointRequest();
        p.setTestSectionId(section.getId());
        p.setPointCode("PT-IMP-" + suffix);
        p.setPointName("导入井口-" + tag);
        p.setWellName("井-" + tag);
        MonitorPoint point = geothermalService.createPoint(p);
        return point.getPointCode();
    }

    private Long pointIdOf(String pointCode) {
        return geothermalService.listPoints(null).stream()
                .filter(p -> p.getPointCode().equals(pointCode)).findFirst().get().getId();
    }

    private MonitorPoint pointOf(String pointCode) {
        return geothermalService.listPoints(null).stream()
                .filter(p -> p.getPointCode().equals(pointCode)).findFirst().get();
    }

    /** 各测试用例共享内存库：观测计数一律按本用例唯一序列号前缀隔离。 */
    private long observationCount(String serialPrefix) {
        return observationMapper.selectCount(
                new QueryWrapper<WellheadObservation>().likeRight("serial_no", serialPrefix));
    }

    private ImportSummaryView doImport(String fileName, String content) {
        ctx(reqNo("imp"));
        return importService.importObservations(fileName,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    private String row(String pointCode, String observedAt, String pressure, String temp,
                       String flow, String injection, String device, String serial) {
        return String.join(",", pointCode, observedAt, pressure, temp, flow, injection, device, serial);
    }

    private List<WellheadObservation> observationsOf(String serialNo) {
        return observationMapper.selectList(
                new QueryWrapper<WellheadObservation>().eq("serial_no", serialNo));
    }

    // ---------- 1. 混合行逐行隔离 ----------

    @Test
    void mixedRows_areIsolatedPerRow_withOriginalRowNoAndReason() {
        String point = createPointChain("mixed");
        String csv = HEADER + "\n" + String.join("\n",
                // 行2：合法
                row(point, "2026-09-10 08:00:00", "1.250", "65.2", "80.5", "120.0", "DEV-A", "SN-A-0001"),
                // 行3：文件内重复（同序列号） -> UPDATED
                row(point, "2026-09-10 08:00:00", "1.300", "65.3", "81.0", "121.0", "DEV-A", "SN-A-0001"),
                // 行4：缺列（只有 5 列）
                point + ",2026-09-10 08:05:00,1.25,65.0,80.0",
                // 行5：坏数值
                row(point, "2026-09-10 08:10:00", "abc", "65.0", "80.0", "100.0", "DEV-A", "SN-A-0002"),
                // 行6：单位量纲超界（5000 MPa 疑似单位错误）
                row(point, "2026-09-10 08:15:00", "5000", "65.0", "80.0", "100.0", "DEV-A", "SN-A-0003"),
                // 行7：业务键不存在
                row("PT-NO-SUCH", "2026-09-10 08:20:00", "1.2", "65.0", "80.0", "100.0", "DEV-A", "SN-A-0004"),
                // 行8：时间格式非法
                row(point, "2026/09/10 八点", "1.2", "65.0", "80.0", "100.0", "DEV-A", "SN-A-0005"),
                // 行9：必填为空
                row(point, "2026-09-10 08:30:00", "", "65.0", "80.0", "100.0", "DEV-A", "SN-A-0006"),
                // 行10：序列号格式非法
                row(point, "2026-09-10 08:35:00", "1.2", "65.0", "80.0", "100.0", "DEV-A", "!!bad"),
                // 行11：未来时间
                row(point, "2099-01-01 00:00:00", "1.2", "65.0", "80.0", "100.0", "DEV-A", "SN-A-0007"));

        ImportSummaryView summary = doImport("mixed.csv", csv);

        assertEquals(ImportFileStatus.COMPLETED_WITH_ERRORS.name(), summary.getStatus());
        assertEquals(10, summary.getTotalRows());
        assertEquals(1, summary.getSuccessRows());
        assertEquals(1, summary.getUpdatedRows());
        assertEquals(8, summary.getFailedRows());
        assertEquals(1, summary.getTotalShards());
        assertEquals(1, summary.getDoneShards());
        assertEquals(0, summary.getFailedShards());

        // 单行失败不回滚整批：合法行已落库，重复行幂等更新（后值覆盖）
        assertEquals(1, observationCount("SN-A-"));
        WellheadObservation obs = observationsOf("SN-A-0001").get(0);
        assertEquals(new BigDecimal("1.300"), obs.getPressureMpa());

        // 失败明细：原始行号、字段、原值、原因
        PageResult<ImportRowView> failed = importService.getRows(summary.getFileId(), "FAILED", 1, 100);
        assertEquals(8, failed.getTotal());
        ImportRowView row4 = failed.getRecords().stream().filter(r -> r.getRowNo() == 4).findFirst().get();
        assertEquals("injectionVolumeM3", row4.getFailField());
        assertTrue(row4.getFailReason().contains("缺列"));
        ImportRowView row5 = failed.getRecords().stream().filter(r -> r.getRowNo() == 5).findFirst().get();
        assertEquals("pressureMpa", row5.getFailField());
        assertEquals("abc", row5.getFailValue());
        assertTrue(row5.getFailReason().contains("数值格式非法"));
        ImportRowView row6 = failed.getRecords().stream().filter(r -> r.getRowNo() == 6).findFirst().get();
        assertTrue(row6.getFailReason().contains("单位校验失败"));
        ImportRowView row7 = failed.getRecords().stream().filter(r -> r.getRowNo() == 7).findFirst().get();
        assertEquals("objectCode", row7.getFailField());
        assertTrue(row7.getFailReason().contains("业务键校验失败"));
        ImportRowView row10 = failed.getRecords().stream().filter(r -> r.getRowNo() == 10).findFirst().get();
        assertEquals("serialNo", row10.getFailField());
        assertTrue(row10.getFailReason().contains("序列校验失败"));

        // 成功/更新明细同样可按行号回查
        PageResult<ImportRowView> updated = importService.getRows(summary.getFileId(), "UPDATED", 1, 10);
        assertEquals(1, updated.getTotal());
        assertEquals(3, updated.getRecords().get(0).getRowNo());
        assertEquals(point + "|SN-A-0001", updated.getRecords().get(0).getBizKey());

        // 审计：一次导入一条 IMPORT_FILE 审计
        assertTrue(auditMapper.selectCount(new QueryWrapper<BizWriteAudit>()
                .eq("object_type", "IMPORT_FILE").eq("object_id", summary.getFileId())) >= 1);
    }

    // ---------- 2. 文件校验和幂等 ----------

    @Test
    void fileChecksumIdempotent_reuploadReturnsSameTaskWithoutDuplicates() {
        String point = createPointChain("idem");
        String csv = HEADER + "\n"
                + row(point, "2026-09-10 09:00:00", "1.1", "60", "70", "100", "DEV-A", "SN-I-0001") + "\n"
                + row(point, "2026-09-10 09:05:00", "1.2", "61", "71", "101", "DEV-A", "SN-I-0002");

        ImportSummaryView first = doImport("same.csv", csv);
        assertEquals(2, first.getSuccessRows());
        assertFalse(first.isIdempotent());

        // 同一文件重复上传：命中校验和幂等，不重复建任务、不重复落库
        ImportSummaryView second = doImport("same.csv", csv);
        assertTrue(second.isIdempotent());
        assertFalse(second.isResumed());
        assertEquals(first.getFileId(), second.getFileId());
        assertEquals(2, observationCount("SN-I-"));
        assertEquals(1, fileMapper.selectCount(new QueryWrapper<ImportFile>()
                .eq("checksum", first.getChecksum())));

        // 内容变化（同序列号新数值）=> 新文件任务，业务键幂等更新
        String csv2 = csv.replace("1.1", "9.9");
        ImportSummaryView third = doImport("same-v2.csv", csv2);
        assertFalse(third.isIdempotent());
        assertNotEquals(first.getFileId(), third.getFileId());
        assertEquals(2, third.getUpdatedRows());
        assertEquals(2, observationCount("SN-I-"));
        assertEquals(new BigDecimal("9.900"), observationsOf("SN-I-0001").get(0).getPressureMpa());
    }

    // ---------- 3. 业务键幂等：网关重发同序列号 / 同时刻异序列号冲突 ----------

    @Test
    void businessKeyIdempotent_gatewayResendAndTimeConflict() {
        String point = createPointChain("gw");
        String csv1 = HEADER + "\n"
                + row(point, "2026-09-11 10:00:00", "2.0", "66", "90", "200", "DEV-GW", "SN-GW-100");
        doImport("gw-1.csv", csv1);
        assertEquals(1, observationCount("SN-GW-"));

        // 网关跨文件重发同序列号（跨天补报重推）：幂等更新，不产生重复记录
        String csv2 = HEADER + "\n"
                + row(point, "2026-09-11 10:00:00", "2.1", "66", "90", "200", "DEV-GW", "SN-GW-100");
        ImportSummaryView s2 = doImport("gw-2.csv", csv2);
        assertEquals(1, s2.getUpdatedRows());
        assertEquals(1, observationCount("SN-GW-"));
        assertEquals(new BigDecimal("2.100"), observationsOf("SN-GW-100").get(0).getPressureMpa());

        // 同一监测点同一观测时刻、不同序列号：业务键冲突，行级失败
        String csv3 = HEADER + "\n"
                + row(point, "2026-09-11 10:00:00", "3.0", "67", "91", "201", "DEV-GW", "SN-GW-101");
        ImportSummaryView s3 = doImport("gw-3.csv", csv3);
        assertEquals(1, s3.getFailedRows());
        PageResult<ImportRowView> failed = importService.getRows(s3.getFileId(), "FAILED", 1, 10);
        assertTrue(failed.getRecords().get(0).getFailReason().contains("业务键冲突"));
        assertEquals(1, observationCount("SN-GW-"));
    }

    // ---------- 4. 已验收 / 已锁定禁止覆盖 ----------

    @Test
    void acceptedBatchAndClosedShift_areNotOverwritten() {
        String point = createPointChain("lock");
        Long pointId = pointIdOf(point);
        LocalDate acceptedDate = LocalDate.of(2026, 9, 10);
        LocalDate closedDate = LocalDate.of(2026, 9, 11);
        LocalDate freeDate = LocalDate.of(2026, 9, 12);

        // 已验收批次：2026-09-10
        Long sectionId = pointOf(point).getTestSectionId();

        ctx(reqNo("sh"));
        ShiftRequest sh1 = new ShiftRequest();
        sh1.setTestSectionId(sectionId);
        sh1.setShiftDate(acceptedDate);
        sh1.setShiftIndex(1);
        ReinjectionShift shift1 = geothermalService.createShift(sh1);

        ctx(reqNo("b"));
        BatchCreateRequest b1 = new BatchCreateRequest();
        b1.setShiftId(shift1.getId());
        b1.setMonitorPointId(pointId);
        MonitorBatch acceptedBatch = geothermalService.createBatch(b1);

        ctx(reqNo("r"));
        ReadingSubmitRequest readings = new ReadingSubmitRequest();
        ReadingSubmitRequest.ReadingItem item = new ReadingSubmitRequest.ReadingItem();
        item.setReadingTimeLocal("2026-09-10T08:00:00");
        item.setPressureMpa(new BigDecimal("1.0"));
        item.setTemperatureC(new BigDecimal("60"));
        item.setFlowM3h(new BigDecimal("70"));
        item.setInjectionVolumeM3(new BigDecimal("90"));
        readings.setReadings(Collections.singletonList(item));
        geothermalService.submitReadings(acceptedBatch.getId(), readings);
        ctx(reqNo("acc"));
        geothermalService.acceptBatch(acceptedBatch.getId());

        // 已闭班锁定：2026-09-11（建班建批后闭班）
        ctx(reqNo("sh"));
        ShiftRequest sh2 = new ShiftRequest();
        sh2.setTestSectionId(sectionId);
        sh2.setShiftDate(closedDate);
        sh2.setShiftIndex(1);
        ReinjectionShift shift2 = geothermalService.createShift(sh2);
        ctx(reqNo("b"));
        BatchCreateRequest b2 = new BatchCreateRequest();
        b2.setShiftId(shift2.getId());
        b2.setMonitorPointId(pointId);
        geothermalService.createBatch(b2);
        ctx(reqNo("close"));
        geothermalService.closeShift(shift2.getId());

        String csv = HEADER + "\n"
                + row(point, "2026-09-10 08:00:00", "1.1", "60", "70", "100", "DEV-A", "SN-L-0001") + "\n"
                + row(point, "2026-09-11 08:00:00", "1.1", "60", "70", "100", "DEV-A", "SN-L-0002") + "\n"
                + row(point, "2026-09-12 08:00:00", "1.1", "60", "70", "100", "DEV-A", "SN-L-0003");
        ImportSummaryView summary = doImport("lock.csv", csv);

        assertEquals(1, summary.getSuccessRows());
        assertEquals(2, summary.getFailedRows());
        PageResult<ImportRowView> failed = importService.getRows(summary.getFileId(), "FAILED", 1, 10);
        assertEquals(2, failed.getTotal());
        assertTrue(failed.getRecords().stream()
                .anyMatch(r -> r.getRowNo() == 2 && r.getFailReason().contains("已验收")));
        assertTrue(failed.getRecords().stream()
                .anyMatch(r -> r.getRowNo() == 3 && r.getFailReason().contains("已闭班锁定")));
        // 已验收/已锁定日期无观测落库；自由日期正常落库
        assertEquals(0, observationsOf("SN-L-0001").size());
        assertEquals(0, observationsOf("SN-L-0002").size());
        assertEquals(1, observationsOf("SN-L-0003").size());
    }

    // ---------- 5. 失败分片重试 / 断点续传 ----------

    @Test
    void failedShardRetry_andBreakpointResume() {
        String point = createPointChain("shard");
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        // 2500 行 -> shardSize 1000 -> 3 个分片（每行不同观测时刻，避免时刻业务键冲突）
        LocalDateTime shardBase = LocalDateTime.of(2026, 9, 10, 0, 0);
        for (int i = 0; i < 2500; i++) {
            sb.append(row(point, shardBase.plusSeconds(i).format(FMT), "1.0", "60", "70", "100",
                    "DEV-A", "SN-S-" + String.format("%05d", i))).append('\n');
        }
        String csv = sb.toString();
        ImportSummaryView first = doImport("shards.csv", csv);
        assertEquals(3, first.getTotalShards());
        assertEquals(2500, first.getSuccessRows());
        assertEquals(ImportFileStatus.COMPLETED.name(), first.getStatus());
        assertEquals(2500, observationCount("SN-S-"));

        // 模拟分片 1 基础设施失败（如导入进程崩溃后状态残留）
        ImportShard shard1 = shardMapper.selectList(new QueryWrapper<ImportShard>()
                        .eq("import_file_id", first.getFileId()).eq("shard_index", 1)).get(0);
        shard1.setStatus(ImportShardStatus.FAILED.name());
        shard1.setErrorMessage("模拟分片失败");
        shardMapper.updateById(shard1);

        // 断点续传：重复上传同一文件 -> 仅续跑失败分片
        ImportSummaryView resumed = doImport("shards.csv", csv);
        assertTrue(resumed.isIdempotent());
        assertTrue(resumed.isResumed());
        assertEquals(ImportFileStatus.COMPLETED.name(), resumed.getStatus());
        assertEquals(3, resumed.getDoneShards());
        assertEquals(0, resumed.getFailedShards());
        // 分片 1 重跑后行级幂等：观测不重复（业务键更新）
        assertEquals(2500, observationCount("SN-S-"));
        ImportShard retried = shardMapper.selectById(shard1.getId());
        assertEquals(ImportShardStatus.SUCCESS.name(), retried.getStatus());
        assertEquals(2, retried.getAttemptCount());
        assertEquals(1000, retried.getUpdatedCount());

        // retry 接口幂等：无待处理分片时直接返回
        ctx(reqNo("retry"));
        ImportSummaryView noop = importService.retryFailedShards(first.getFileId());
        assertFalse(noop.isResumed());
        assertEquals(ImportFileStatus.COMPLETED.name(), noop.getStatus());

        // 行明细仍完整（重试先清后写，(file,rowNo) 唯一）
        assertEquals(2500, rowMapper.selectCount(new QueryWrapper<com.evops.geothermal.entity.ImportRow>()
                .eq("import_file_id", first.getFileId())));
    }

    // ---------- 6. 坏传感器隔离 ----------

    @Test
    void quarantinedDevice_isIsolatedPerRow() {
        String point = createPointChain("quar");
        ctx(reqNo("quar"));
        quarantineService.quarantine("DEV-BAD", "压力传感器漂移");

        String csv = HEADER + "\n"
                + row(point, "2026-09-10 08:00:00", "1.1", "60", "70", "100", "DEV-BAD", "SN-Q-0001") + "\n"
                + row(point, "2026-09-10 08:05:00", "1.1", "60", "70", "100", "DEV-OK", "SN-Q-0002");
        ImportSummaryView summary = doImport("quar.csv", csv);
        assertEquals(1, summary.getSuccessRows());
        assertEquals(1, summary.getFailedRows());

        PageResult<ImportRowView> failed = importService.getRows(summary.getFileId(), "FAILED", 1, 10);
        assertEquals("sourceDevice", failed.getRecords().get(0).getFailField());
        assertEquals("DEV-BAD", failed.getRecords().get(0).getFailValue());
        assertTrue(failed.getRecords().get(0).getFailReason().contains("坏传感器"));
        assertEquals(0, observationsOf("SN-Q-0001").size());
        assertEquals(1, observationsOf("SN-Q-0002").size());

        // 解除隔离后同设备可正常导入
        ctx(reqNo("rel"));
        quarantineService.release("DEV-BAD");
        String csv2 = HEADER + "\n"
                + row(point, "2026-09-10 08:00:00", "1.2", "60", "70", "100", "DEV-BAD", "SN-Q-0001");
        ImportSummaryView s2 = doImport("quar-2.csv", csv2);
        assertEquals(1, s2.getSuccessRows());
        assertEquals(1, observationsOf("SN-Q-0001").size());
    }

    // ---------- 7. 跨天补报：biz_date 按观测日派生 ----------

    @Test
    void crossDayBackfill_bizDateDerivedFromObservedAt() {
        String point = createPointChain("backfill");
        String csv = HEADER + "\n"
                + row(point, "2026-09-10 23:59:00", "1.1", "60", "70", "100", "DEV-A", "SN-B-0001") + "\n"
                + row(point, "2026-09-11 00:01:00", "1.1", "60", "70", "100", "DEV-A", "SN-B-0002") + "\n"
                + row(point, "2026-09-12 12:00:00", "1.1", "60", "70", "100", "DEV-A", "SN-B-0003");
        ImportSummaryView summary = doImport("backfill.csv", csv);
        assertEquals(3, summary.getSuccessRows());
        assertEquals(LocalDate.of(2026, 9, 10), observationsOf("SN-B-0001").get(0).getBizDate());
        assertEquals(LocalDate.of(2026, 9, 11), observationsOf("SN-B-0002").get(0).getBizDate());
        assertEquals(LocalDate.of(2026, 9, 12), observationsOf("SN-B-0003").get(0).getBizDate());
    }

    // ---------- 8. 50,000 行分片处理 ----------

    @Test
    void largeFile50k_processedInShardsWithoutRollback() {
        String point = createPointChain("50k");
        int total = 50_000;
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        LocalDateTime base = LocalDateTime.of(2026, 8, 1, 0, 0);
        for (int i = 0; i < total; i++) {
            sb.append(point).append(',')
                    .append(base.plusMinutes(i).format(FMT)).append(',')
                    .append("1.500,65.000,80.000,120.000,DEV-BULK,SN-K-")
                    .append(String.format("%07d", i)).append('\n');
        }
        ImportSummaryView summary = doImport("bulk-50k.csv", sb.toString());

        assertEquals(total, summary.getTotalRows());
        assertEquals(total, summary.getSuccessRows());
        assertEquals(0, summary.getFailedRows());
        // shardSize=1000 -> 50 个分片全部成功
        assertEquals(50, summary.getTotalShards());
        assertEquals(50, summary.getDoneShards());
        assertEquals(0, summary.getFailedShards());
        assertEquals(ImportFileStatus.COMPLETED.name(), summary.getStatus());
        assertEquals(total, observationCount("SN-K-"));
        assertEquals(total, rowMapper.selectCount(
                new QueryWrapper<com.evops.geothermal.entity.ImportRow>()
                        .eq("import_file_id", summary.getFileId())));
        // 跨天分布（50000 分钟覆盖约 34 天）
        List<WellheadObservation> sample = observationMapper.selectList(
                new QueryWrapper<WellheadObservation>().eq("serial_no", "SN-K-0049999"));
        assertEquals(base.plusMinutes(total - 1).toLocalDate(), sample.get(0).getBizDate());
    }

    // ---------- 9. 并发同文件上传：校验和幂等闸门 ----------

    @Test
    void concurrentSameFileUpload_singleTaskNoDuplicates() throws Exception {
        String point = createPointChain("conc");
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        LocalDateTime base = LocalDateTime.of(2026, 9, 10, 6, 0);
        for (int i = 0; i < 200; i++) {
            sb.append(row(point, base.plusSeconds(i).format(FMT), "1.0", "60", "70", "100",
                    "DEV-C", "SN-C-" + String.format("%05d", i))).append('\n');
        }
        String csv = sb.toString();

        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<ImportSummaryView> summaries = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger errors = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    summaries.add(doImport("conc.csv", csv));
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS));

        assertEquals(0, errors.get());
        assertEquals(threads, summaries.size());
        // 5 路并发全部命中同一导入任务（校验和幂等闸门）
        Long fileId = summaries.get(0).getFileId();
        assertTrue(summaries.stream().allMatch(s -> s.getFileId().equals(fileId)));
        // 观测不重复、终态完成
        assertEquals(200, observationCount("SN-C-"));
        ImportSummaryView finalSummary = importService.getSummary(fileId);
        assertEquals(ImportFileStatus.COMPLETED.name(), finalSummary.getStatus());
        assertEquals(200, finalSummary.getSuccessRows() + finalSummary.getUpdatedRows());
        assertEquals(0, finalSummary.getFailedShards());
    }
}
