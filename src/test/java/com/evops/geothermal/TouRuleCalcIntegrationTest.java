package com.evops.geothermal;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BizException;
import com.evops.common.RequestContext;
import com.evops.geothermal.dto.MonitorPointRequest;
import com.evops.geothermal.dto.TestSectionRequest;
import com.evops.geothermal.dto.TouCalcRequest;
import com.evops.geothermal.dto.TouChainCreateRequest;
import com.evops.geothermal.dto.TouIntervalUpdateRequest;
import com.evops.geothermal.dto.TouVersionCloneRequest;
import com.evops.geothermal.dto.TouVersionDraftRequest;
import com.evops.geothermal.dto.WellGroupRequest;
import com.evops.geothermal.entity.BizWriteAudit;
import com.evops.geothermal.entity.ImportFile;
import com.evops.geothermal.entity.MonitorPoint;
import com.evops.geothermal.entity.TouCalcDetail;
import com.evops.geothermal.entity.TouCalcResult;
import com.evops.geothermal.entity.TouInterval;
import com.evops.geothermal.entity.TouRuleChain;
import com.evops.geothermal.entity.TouRuleSet;
import com.evops.geothermal.entity.WellGroup;
import com.evops.geothermal.entity.WellheadObservation;
import com.evops.geothermal.enums.SegmentType;
import com.evops.geothermal.enums.TouRuleStatus;
import com.evops.geothermal.enums.TouTargetType;
import com.evops.geothermal.mapper.BizWriteAuditMapper;
import com.evops.geothermal.mapper.ImportFileMapper;
import com.evops.geothermal.mapper.TouCalcDetailMapper;
import com.evops.geothermal.mapper.TouCalcResultMapper;
import com.evops.geothermal.mapper.TouIntervalMapper;
import com.evops.geothermal.mapper.TouRuleChainMapper;
import com.evops.geothermal.mapper.TouRuleSetMapper;
import com.evops.geothermal.mapper.WellheadObservationMapper;
import com.evops.geothermal.service.GeothermalService;
import com.evops.geothermal.service.TouCalcService;
import com.evops.geothermal.service.TouRuleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 井口监测时序规则（峰平谷）版本化与试验窗口规则计算集成测试。
 *
 * 覆盖困难级约束：
 *  - 至少 4 个版本化规则（v1..v4，区间/阈值各不相同，v4 改变跨午夜谷段形状）；
 *  - 业务时区跨日：井场时区 Asia/Shanghai 与 Asia/Urumqi，跨午夜谷段 dayOffset=-1；
 *  - 左闭右开：窗口端点、区间端点时刻归属；
 *  - 规则区间重叠拒绝、未覆盖拒绝、缺峰平谷拒绝；
 *  - 启用后不可原地修改，只能新版本；历史结果读取当时快照（阈值/系数/版本）；
 *  - 压力阈值按生效时间选版本；BigDecimal 精确累计、最终统一 HALF_UP 舍入；
 *  - 跨午夜班次不重复计量（窗口重叠拒绝 + 每观测唯一明细）；
 *  - 5 路并发重算只产生一份结果与一条审计。
 */
@SpringBootTest
@ActiveProfiles("test")
class TouRuleCalcIntegrationTest {

    @Autowired private GeothermalService geothermalService;
    @Autowired private TouRuleService ruleService;
    @Autowired private TouCalcService calcService;
    @Autowired private TouRuleChainMapper chainMapper;
    @Autowired private TouRuleSetMapper setMapper;
    @Autowired private TouIntervalMapper intervalMapper;
    @Autowired private TouCalcResultMapper resultMapper;
    @Autowired private TouCalcDetailMapper detailMapper;
    @Autowired private WellheadObservationMapper observationMapper;
    @Autowired private ImportFileMapper importFileMapper;
    @Autowired private BizWriteAuditMapper auditMapper;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private String suffix;
    private Long importFileId;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        ctx("setup");
        ImportFile importFile = new ImportFile();
        importFile.setTenantId(1L);
        importFile.setFileName("tou-" + suffix + ".csv");
        importFile.setChecksum("sha256-" + suffix);
        importFile.setShardSize(1000);
        importFile.setStatus("COMPLETED");
        importFileMapper.insert(importFile);
        importFileId = importFile.getId();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    private void ctx(String tag) {
        RequestContext.set(new RequestContext.Ctx(
                "REQ-" + tag + "-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 6),
                2001L, "规则管理员", "Asia/Shanghai"));
    }

    // ============================ 造数 helpers ============================

    private MonitorPoint createPointWithChain(String timezone) {
        ctx("wg");
        WellGroupRequest g = new WellGroupRequest();
        g.setGroupCode("WG-TOU-" + suffix);
        g.setGroupName("时序规则井组");
        g.setLocation("咸阳");
        g.setTimezone(timezone);
        WellGroup group = geothermalService.createGroup(g);

        ctx("sec");
        TestSectionRequest s = new TestSectionRequest();
        s.setWellGroupId(group.getId());
        s.setSectionCode("SEC-TOU-" + suffix);
        s.setSectionName("时序规则试验段");
        com.evops.geothermal.entity.TestSection section = geothermalService.createSection(s);

        ctx("pt");
        MonitorPointRequest p = new MonitorPointRequest();
        p.setTestSectionId(section.getId());
        p.setPointCode("PT-TOU-" + suffix);
        p.setPointName("时序监测点");
        p.setWellName("DR-" + suffix);
        MonitorPoint point = geothermalService.createPoint(p);

        ctx("chain");
        TouChainCreateRequest chainReq = new TouChainCreateRequest();
        chainReq.setChainCode("CHAIN-" + suffix);
        chainReq.setChainName("峰平谷规则链");
        chainReq.setTargetType(TouTargetType.MONITOR_POINT.name());
        chainReq.setTargetId(point.getId());
        ruleService.createChain(chainReq);
        return point;
    }

    /** 标准三段（谷 22-06 跨午夜 / 平 06-18 / 峰 18-22）。 */
    private List<TouVersionDraftRequest.IntervalItem> standardIntervals() {
        return intervals(
                iv("VALLEY_NIGHT", "VALLEY", 1320, 360, "0.500000"),
                iv("FLAT_DAY", "FLAT", 360, 1080, "1.000000"),
                iv("PEAK_EVE", "PEAK", 1080, 1320, "1.500000"));
    }

    /** v4：谷段形状改为 23-07 跨午夜 / 平 07-17 / 峰 17-23。 */
    private List<TouVersionDraftRequest.IntervalItem> shiftedIntervals() {
        return intervals(
                iv("VALLEY_NIGHT", "VALLEY", 1380, 420, "0.500000"),
                iv("FLAT_DAY", "FLAT", 420, 1020, "1.000000"),
                iv("PEAK_EVE", "PEAK", 1020, 1380, "1.500000"));
    }

    private TouVersionDraftRequest.IntervalItem iv(String code, String seg, int s, int e, String coeff) {
        TouVersionDraftRequest.IntervalItem item = new TouVersionDraftRequest.IntervalItem();
        item.setIntervalCode(code);
        item.setSegmentType(seg);
        item.setStartMinute(s);
        item.setEndMinute(e);
        item.setPriceCoefficient(new BigDecimal(coeff));
        return item;
    }

    private List<TouVersionDraftRequest.IntervalItem> intervals(TouVersionDraftRequest.IntervalItem... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    private Long newVersion(Long chainId, Long sourceSetId, LocalDateTime from,
                            String threshold, List<TouVersionDraftRequest.IntervalItem> intervalItems) {
        if (sourceSetId == null) {
            ctx("draft");
            TouVersionDraftRequest req = new TouVersionDraftRequest();
            req.setEffectiveFrom(from);
            req.setPressureThresholdMpa(new BigDecimal(threshold));
            req.setIntervals(intervalItems);
            return ruleService.createDraft(chainId, req).getId();
        }
        ctx("clone");
        TouVersionCloneRequest clone = new TouVersionCloneRequest();
        clone.setEffectiveFrom(from);
        clone.setPressureThresholdMpa(new BigDecimal(threshold));
        Long draftId = ruleService.cloneAsDraft(sourceSetId, clone).getId();
        if (intervalItems != null) {
            // 用新形状替换：删除旧区间后逐个新增（草稿状态允许）；每次写审计需独立请求号
            List<TouInterval> olds = ruleService.listIntervalRows(draftId);
            int i = 0;
            for (TouInterval old : olds) {
                ctx("del-interval-" + (i++));
                ruleService.deleteInterval(draftId, old.getIntervalCode());
            }
            for (TouVersionDraftRequest.IntervalItem item : intervalItems) {
                ctx("add-interval-" + (i++));
                TouIntervalUpdateRequest up = new TouIntervalUpdateRequest();
                up.setSegmentType(item.getSegmentType());
                up.setStartMinute(item.getStartMinute());
                up.setEndMinute(item.getEndMinute());
                up.setPriceCoefficient(item.getPriceCoefficient());
                ruleService.addInterval(draftId, up, item.getIntervalCode());
            }
        }
        return draftId;
    }

    private TouRuleSet enable(Long setId) {
        ctx("enable");
        return ruleService.enableVersion(setId);
    }

    private int rowSeq = 0;

    private void obs(MonitorPoint point, String at, String pressure, String volume) {
        LocalDateTime t = LocalDateTime.parse(at, ISO);
        WellheadObservation o = new WellheadObservation();
        o.setTenantId(1L);
        o.setMonitorPointId(point.getId());
        o.setObjectCode(point.getPointCode());
        o.setSerialNo("SN-" + suffix + "-" + (++rowSeq));
        o.setSourceDevice("DEV-TOU");
        o.setObservedAt(t);
        o.setBizDate(t.toLocalDate());
        o.setPressureMpa(new BigDecimal(pressure));
        o.setTemperatureC(new BigDecimal("65.000"));
        o.setFlowM3h(new BigDecimal("80.000"));
        o.setInjectionVolumeM3(new BigDecimal(volume));
        o.setImportFileId(importFileId);
        o.setRowNo(rowSeq);
        observationMapper.insert(o);
    }

    private TouCalcResult calc(Long pointId, String start, String end) {
        TouCalcRequest req = new TouCalcRequest();
        req.setTargetType(TouTargetType.MONITOR_POINT.name());
        req.setTargetId(pointId);
        req.setWindowStart(LocalDateTime.parse(start, ISO));
        req.setWindowEnd(LocalDateTime.parse(end, ISO));
        return calcService.calculate(req);
    }

    private List<TouCalcDetail> details(Long resultId) {
        return detailMapper.selectList(new QueryWrapper<TouCalcDetail>()
                .eq("calc_result_id", resultId).orderByAsc("observed_at", "id"));
    }

    private TouCalcDetail findDetail(List<TouCalcDetail> list, String at) {
        LocalDateTime t = LocalDateTime.parse(at, ISO);
        for (TouCalcDetail d : list) {
            if (d.getObservedAt().equals(t)) {
                return d;
            }
        }
        throw new AssertionError("未找到观测明细: " + at);
    }

    // ============================ 主场景：4 版本 + 跨午夜 + 左闭右开 + 快照 ============================

    @Test
    void fourVersions_crossMidnight_leftClosedRightOpen_thresholdByEffectiveTime_snapshot() {
        MonitorPoint point = createPointWithChain("Asia/Shanghai");
        TouRuleChain chain = chainMapper.selectOne(new QueryWrapper<TouRuleChain>()
                .eq("target_id", point.getId()), false);

        // ---- 至少 4 个版本化规则；阈值 1.2 → 1.4 → 1.6 → 1.8，v4 区间形状变化 ----
        Long v1 = newVersion(chain.getId(), null,
                LocalDateTime.of(2026, 9, 10, 0, 0), "1.200", standardIntervals());
        enable(v1);
        Long v2 = newVersion(chain.getId(), v1,
                LocalDateTime.of(2026, 9, 11, 0, 0), "1.400", null);
        enable(v2);
        Long v3 = newVersion(chain.getId(), v2,
                LocalDateTime.of(2026, 9, 12, 0, 0), "1.600", null);
        enable(v3);
        Long v4 = newVersion(chain.getId(), v3,
                LocalDateTime.of(2026, 9, 13, 0, 0), "1.800", shiftedIntervals());
        TouRuleSet enabledV4 = enable(v4);

        // 旧版本闭合为 SUPERSEDED，生效窗口左闭右开
        assertEquals(TouRuleStatus.ENABLED.name(), enabledV4.getStatus());
        assertNull(enabledV4.getEffectiveTo());
        assertEquals(LocalDateTime.of(2026, 9, 13, 0, 0), setMapper.selectById(v3).getEffectiveTo());
        assertEquals(TouRuleStatus.SUPERSEDED.name(), setMapper.selectById(v1).getStatus());
        assertEquals(LocalDateTime.of(2026, 9, 11, 0, 0), setMapper.selectById(v1).getEffectiveTo());
        assertEquals(v4, chainMapper.selectById(chain.getId()).getCurrentVersionId());

        // ---- 观测：窗口 [09-10 22:00, 09-13 06:00)，12 条计入 + 1 条落在终点被排除 ----
        // 说明：阈值版本严格按“观测时刻”选取；跨午夜区间实例归属前一日（两个维度相互独立）。
        obs(point, "2026-09-10T22:00:00", "1.300", "100"); // v1 谷, 1.3>1.2 超阈
        obs(point, "2026-09-10T22:30:00", "1.100", "100"); // v1 谷
        obs(point, "2026-09-11T00:30:00", "1.300", "100"); // 时刻已入 v2（阈值1.4未超）；谷段实例归属 09-10
        obs(point, "2026-09-11T06:00:00", "1.300", "100"); // v2 平（左闭右开边界）
        obs(point, "2026-09-11T18:00:00", "1.500", "200"); // v2 峰, 超阈
        obs(point, "2026-09-11T22:00:00", "1.100", "100"); // v2 谷
        obs(point, "2026-09-12T00:30:00", "1.100", "100"); // 时刻已入 v3（阈值1.6）；谷段实例归属 09-11
        obs(point, "2026-09-12T06:00:00", "1.500", "100"); // v3 平
        obs(point, "2026-09-12T18:00:00", "1.700", "200"); // v3 峰, 超阈
        obs(point, "2026-09-12T23:30:00", "1.100", "100"); // v3 谷（22-06 形状, 当日实例）
        obs(point, "2026-09-13T00:30:00", "1.100", "100"); // v4 谷（23-07 新形状, 跨午夜归属 09-12）
        obs(point, "2026-09-13T05:59:00", "1.900", "100"); // v4 谷, 超阈；窗口终点前最后一分钟
        obs(point, "2026-09-13T06:00:00", "1.000", "999"); // 恰在窗口终点（不含），排除

        ctx("calc");
        TouCalcResult result = calc(point.getId(), "2026-09-10T22:00:00", "2026-09-13T06:00:00");
        assertEquals("Asia/Shanghai", result.getWindowTimezone());
        assertEquals(12, result.getObservationCount());

        List<TouCalcDetail> ds = details(result.getId());
        // 每条观测仅一条明细（跨午夜不重复计量）
        long uniqueObs = ds.stream().map(TouCalcDetail::getObservationId).distinct().count();
        assertEquals(12, uniqueObs);

        // ---- 左闭右开：窗口起点计入，终点排除 ----
        assertEquals(12, ds.size());
        assertNull(ds.stream().filter(d -> d.getObservedAt().equals(LocalDateTime.of(2026, 9, 13, 6, 0)))
                .findFirst().orElse(null));

        // ---- 压力阈值按生效时间选版本（严格按观测时刻，与区间实例归属相互独立） ----
        assertEquals(v1, findDetail(ds, "2026-09-10T22:00:00").getRuleSetId());
        assertEquals(1, findDetail(ds, "2026-09-10T22:00:00").getRuleVersionNo());
        assertEquals(new BigDecimal("1.200"), findDetail(ds, "2026-09-10T22:00:00").getPressureThresholdMpa());
        assertEquals(new BigDecimal("1.400"), findDetail(ds, "2026-09-11T00:30:00").getPressureThresholdMpa());
        assertEquals(v2, findDetail(ds, "2026-09-11T00:30:00").getRuleSetId());
        assertEquals(new BigDecimal("1.400"), findDetail(ds, "2026-09-11T06:00:00").getPressureThresholdMpa());
        assertEquals(new BigDecimal("1.600"), findDetail(ds, "2026-09-12T06:00:00").getPressureThresholdMpa());
        assertEquals(new BigDecimal("1.600"), findDetail(ds, "2026-09-12T23:30:00").getPressureThresholdMpa());
        assertEquals(new BigDecimal("1.800"), findDetail(ds, "2026-09-13T05:59:00").getPressureThresholdMpa());

        // 超阈判定严格大于（00:30 虽归属前一日谷段实例，阈值仍按观测时刻取 v2 的 1.4，1.3 不超阈）
        assertTrue(findDetail(ds, "2026-09-10T22:00:00").getThresholdBreached());
        assertFalse(findDetail(ds, "2026-09-11T00:30:00").getThresholdBreached());
        assertFalse(findDetail(ds, "2026-09-11T06:00:00").getThresholdBreached()); // 1.3 > 1.4 false
        long breachCount = ds.stream().filter(d -> Boolean.TRUE.equals(d.getThresholdBreached())).count();
        assertEquals(4, breachCount); // 22:00(v1), 18:00(v2), 18:00(v3), 05:59(v4)

        // ---- 跨午夜归类 + dayOffset ----
        TouCalcDetail cross1 = findDetail(ds, "2026-09-11T00:30:00");
        assertEquals(SegmentType.VALLEY.name(), cross1.getSegmentType());
        assertEquals(-1, cross1.getDayOffset());
        assertEquals(java.time.LocalDate.of(2026, 9, 10), cross1.getBizDate());
        assertEquals(LocalDateTime.of(2026, 9, 10, 22, 0), cross1.getSegmentStart());
        assertEquals(LocalDateTime.of(2026, 9, 11, 6, 0), cross1.getSegmentEnd());
        TouCalcDetail cross2 = findDetail(ds, "2026-09-13T00:30:00");
        assertEquals(-1, cross2.getDayOffset());
        assertEquals(LocalDateTime.of(2026, 9, 12, 23, 0), cross2.getSegmentStart()); // v4 新形状 23-07
        assertEquals(LocalDateTime.of(2026, 9, 13, 7, 0), cross2.getSegmentEnd());

        // ---- 左闭右开区间端点：06:00 归平段、18:00 归峰段、22:00 归谷段 ----
        assertEquals(SegmentType.FLAT.name(), findDetail(ds, "2026-09-11T06:00:00").getSegmentType());
        assertEquals(SegmentType.PEAK.name(), findDetail(ds, "2026-09-11T18:00:00").getSegmentType());
        assertEquals(SegmentType.VALLEY.name(), findDetail(ds, "2026-09-11T22:00:00").getSegmentType());

        // ---- BigDecimal 精确累计，最终统一舍入：8×50 + 2×100 + 2×300 = 1200.000 ----
        assertEquals(new BigDecimal("1200.000"), result.getTotalScaledInjectionM3());
        Map<String, BigDecimal> segTotals = new HashMap<>();
        for (TouCalcDetail d : ds) {
            segTotals.merge(d.getSegmentType(), d.getScaledInjectionM3(), BigDecimal::add);
        }
        assertEquals(0, segTotals.get("PEAK").compareTo(new BigDecimal("600")));
        assertEquals(0, segTotals.get("FLAT").compareTo(new BigDecimal("200")));
        assertEquals(0, segTotals.get("VALLEY").compareTo(new BigDecimal("400")));
        // 明细保留 9 位精确乘积，不做行内舍入
        assertEquals(new BigDecimal("300.000000000"),
                findDetail(ds, "2026-09-11T18:00:00").getScaledInjectionM3());

        // 计算快照嵌入 4 个版本的冻结规则
        assertTrue(result.getCalcSnapshot().contains("\"versionNo\":4"));
        assertTrue(result.getCalcSnapshot().contains("\"versionNo\":1"));
        assertTrue(result.getCalcSnapshot().contains("frozenRule"));
        assertTrue(result.getCalcSnapshot().contains("exactTotalBeforeRounding"));

        // ============================ 历史结果读取当时快照：v5 上线后历史不变 ============================
        String v4SnapshotBefore = setMapper.selectById(v4).getFrozenSnapshot();
        Long v5 = newVersion(chain.getId(), v4,
                LocalDateTime.of(2026, 9, 14, 0, 0), "2.500", shiftedIntervals());
        enable(v5);

        Map<String, Object> view = calcService.getResultView(result.getId());
        @SuppressWarnings("unchecked")
        List<TouCalcDetail> viewDetails = (List<TouCalcDetail>) view.get("details");
        assertEquals(12, viewDetails.size());
        // v4 阈值 1.8 仍在历史明细中（现行版本已是 2.5）
        assertEquals(new BigDecimal("1.800"),
                findDetail(viewDetails, "2026-09-13T05:59:00").getPressureThresholdMpa());
        assertEquals(v4, findDetail(viewDetails, "2026-09-13T05:59:00").getRuleSetId());
        // v4 冻结快照未被新版本改写
        assertEquals(v4SnapshotBefore, setMapper.selectById(v4).getFrozenSnapshot());
        assertEquals(new BigDecimal("1200.000"), ((TouCalcResult) view.get("result")).getTotalScaledInjectionM3());
    }

    // ============================ 规则维护：重叠/缺口/三类齐备/冻结不可改 ============================

    @Test
    void enableRule_rejectsOverlap_gap_missingSegments_andFrozenImmutable() {
        MonitorPoint point = createPointWithChain("Asia/Shanghai");
        TouRuleChain chain = chainMapper.selectOne(new QueryWrapper<TouRuleChain>()
                .eq("target_id", point.getId()), false);

        // 区间重叠（359 与谷段 0-360 相交一分钟）→ 草稿建档阶段即拒绝，事务回滚不留草稿
        assertEquals("INTERVAL_OVERLAP", assertThrows(BizException.class,
                () -> newVersion(chain.getId(), null, LocalDateTime.of(2026, 9, 10, 0, 0),
                        "1.200", intervals(
                                iv("V", "VALLEY", 0, 360, "0.5"),
                                iv("F", "FLAT", 359, 1080, "1.0"),
                                iv("P", "PEAK", 1080, 1320, "1.5"),
                                iv("V2", "VALLEY", 1320, 1440, "0.5")))).getCode());

        // 未覆盖全天（22-24 缺口）→ 拒绝
        Long bad2 = newVersion(chain.getId(), null, LocalDateTime.of(2026, 9, 10, 0, 0),
                "1.200", intervals(
                        iv("V", "VALLEY", 0, 360, "0.5"),
                        iv("F", "FLAT", 360, 1080, "1.0"),
                        iv("P", "PEAK", 1080, 1320, "1.5")));
        assertEquals("INTERVAL_NOT_COVERED",
                assertThrows(BizException.class, () -> enable(bad2)).getCode());
        ruleService.deleteDraft(bad2);

        // 缺谷段（只有峰/平）→ 拒绝
        Long bad3 = newVersion(chain.getId(), null, LocalDateTime.of(2026, 9, 10, 0, 0),
                "1.200", intervals(
                        iv("F", "FLAT", 0, 1080, "1.0"),
                        iv("P", "PEAK", 1080, 1440, "1.5")));
        assertEquals("SEGMENT_INCOMPLETE",
                assertThrows(BizException.class, () -> enable(bad3)).getCode());
        ruleService.deleteDraft(bad3);

        // 草稿阶段重叠也被即时拒绝（addInterval）
        Long draft = newVersion(chain.getId(), null, LocalDateTime.of(2026, 9, 10, 0, 0),
                "1.200", standardIntervals());
        TouIntervalUpdateRequest extra = new TouIntervalUpdateRequest();
        extra.setSegmentType(SegmentType.PEAK.name());
        extra.setStartMinute(300);
        extra.setEndMinute(400);
        extra.setPriceCoefficient(new BigDecimal("2.0"));
        assertEquals("INTERVAL_OVERLAP", assertThrows(BizException.class,
                () -> ruleService.addInterval(draft, extra, "PEAK_EXTRA")).getCode());

        // 合法启用
        TouRuleSet enabled = enable(draft);
        assertEquals(TouRuleStatus.ENABLED.name(), enabled.getStatus());
        assertNotNull(enabled.getFrozenSnapshot());
        List<TouInterval> frozenRows = intervalMapper.selectList(new QueryWrapper<TouInterval>()
                .eq("rule_set_id", draft));

        // 启用后原地修改/删除一律拒绝
        TouIntervalUpdateRequest change = new TouIntervalUpdateRequest();
        change.setSegmentType(SegmentType.FLAT.name());
        change.setStartMinute(360);
        change.setEndMinute(1080);
        change.setPriceCoefficient(new BigDecimal("9.99"));
        assertEquals("FROZEN_RULE", assertThrows(BizException.class,
                () -> ruleService.updateInterval(draft, "FLAT_DAY", change)).getCode());
        assertEquals("FROZEN_RULE", assertThrows(BizException.class,
                () -> ruleService.deleteInterval(draft, "FLAT_DAY")).getCode());
        assertEquals("FROZEN_RULE", assertThrows(BizException.class,
                () -> ruleService.deleteDraft(draft)).getCode());
        assertEquals("FROZEN_RULE", assertThrows(BizException.class,
                () -> enable(draft)).getCode());
        // 区间行原样未动
        List<TouInterval> afterRows = intervalMapper.selectList(new QueryWrapper<TouInterval>()
                .eq("rule_set_id", draft));
        assertEquals(frozenRows.size(), afterRows.size());
        for (int i = 0; i < frozenRows.size(); i++) {
            assertEquals(frozenRows.get(i).getPriceCoefficient(), afterRows.get(i).getPriceCoefficient());
        }

        // 只能通过新版本迭代：克隆后改区间不影响历史版本
        ctx("clone2");
        TouVersionCloneRequest cloneReq = new TouVersionCloneRequest();
        cloneReq.setEffectiveFrom(LocalDateTime.of(2026, 10, 1, 0, 0));
        cloneReq.setPressureThresholdMpa(new BigDecimal("2.000"));
        Long nextDraft = ruleService.cloneAsDraft(draft, cloneReq).getId();
        TouIntervalUpdateRequest newFlat = new TouIntervalUpdateRequest();
        newFlat.setSegmentType(SegmentType.FLAT.name());
        newFlat.setStartMinute(360);
        newFlat.setEndMinute(1080);
        newFlat.setPriceCoefficient(new BigDecimal("1.200000"));
        ctx("upd2");
        ruleService.updateInterval(nextDraft, "FLAT_DAY", newFlat);
        TouRuleSet next = enable(nextDraft);
        assertEquals(2, next.getVersionNo());
        // 旧版本系数仍为 1.0，新版本为 1.2
        assertEquals(new BigDecimal("1.000000"), intervalMapper.selectOne(new QueryWrapper<TouInterval>()
                .eq("rule_set_id", draft).eq("interval_code", "FLAT_DAY"), false).getPriceCoefficient());
        assertEquals(new BigDecimal("1.200000"), intervalMapper.selectOne(new QueryWrapper<TouInterval>()
                .eq("rule_set_id", nextDraft).eq("interval_code", "FLAT_DAY"), false).getPriceCoefficient());
        // 旧版本被闭合
        assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0), setMapper.selectById(draft).getEffectiveTo());
        assertEquals(TouRuleStatus.SUPERSEDED.name(), setMapper.selectById(draft).getStatus());
    }

    // ============================ BigDecimal 最终统一舍入 ============================

    @Test
    void bigDecimalAccumulation_roundOnlyAtFinalStep() {
        MonitorPoint point = createPointWithChain("Asia/Shanghai");
        TouRuleChain chain = chainMapper.selectOne(new QueryWrapper<TouRuleChain>()
                .eq("target_id", point.getId()), false);
        Long v1 = newVersion(chain.getId(), null, LocalDateTime.of(2026, 9, 10, 0, 0),
                "1.200", intervals(
                        iv("V", "VALLEY", 0, 480, "0.333333"),
                        iv("F", "FLAT", 480, 960, "1.000000"),
                        iv("P", "PEAK", 960, 1440, "1.000000")));
        enable(v1);

        // 3 × (1 m³ × 0.333333) = 0.999999；若逐行舍入会得 0.999，最终统一舍入得 1.000
        obs(point, "2026-09-10T07:00:00", "1.000", "1");
        obs(point, "2026-09-10T07:01:00", "1.000", "1");
        obs(point, "2026-09-10T07:02:00", "1.000", "1");

        ctx("calc");
        TouCalcResult result = calc(point.getId(), "2026-09-10T00:00:00", "2026-09-11T00:00:00");
        assertEquals(new BigDecimal("1.000"), result.getTotalScaledInjectionM3());
        assertEquals(3, result.getObservationCount());
        for (TouCalcDetail d : details(result.getId())) {
            // 行内保留精确值 0.333333000，绝不提前舍入
            assertEquals(new BigDecimal("0.333333000"), d.getScaledInjectionM3());
        }
        // 快照记录舍入前精确合计
        assertTrue(result.getCalcSnapshot().contains("\"exactTotalBeforeRounding\":\"0.999999000\""));
    }

    // ============================ 窗口：幂等 / 重叠拒绝 / 邻接不重复计量 / 并发唯一 ============================

    @Test
    void windowIdempotent_overlapRejected_adjacentAllowed() {
        MonitorPoint point = createPointWithChain("Asia/Shanghai");
        TouRuleChain chain = chainMapper.selectOne(new QueryWrapper<TouRuleChain>()
                .eq("target_id", point.getId()), false);
        Long v1 = newVersion(chain.getId(), null, LocalDateTime.of(2026, 9, 10, 0, 0),
                "1.200", standardIntervals());
        enable(v1);

        obs(point, "2026-09-10T22:30:00", "1.000", "100");
        obs(point, "2026-09-11T06:00:00", "1.000", "50"); // 与下一邻接窗口共享端点

        ctx("calc1");
        TouCalcResult first = calc(point.getId(), "2026-09-10T22:00:00", "2026-09-11T06:00:00");
        assertEquals(1, first.getObservationCount());

        // 同窗口重放：幂等返回同一结果，不新增头/明细/审计
        ctx("calc1-repeat");
        TouCalcResult repeat = calc(point.getId(), "2026-09-10T22:00:00", "2026-09-11T06:00:00");
        assertEquals(first.getId(), repeat.getId());
        assertEquals(1, resultMapper.selectCount(new QueryWrapper<TouCalcResult>().eq("id", first.getId())));
        assertEquals(1, detailMapper.selectCount(new QueryWrapper<TouCalcDetail>().eq("calc_result_id", first.getId())));

        // 与已算窗口相交（左闭右开相交）→ 拒绝，跨午夜班次不得重复计量
        ctx("calc-overlap");
        BizException overlap = assertThrows(BizException.class,
                () -> calc(point.getId(), "2026-09-11T00:00:00", "2026-09-11T12:00:00"));
        assertEquals("WINDOW_OVERLAP", overlap.getCode());

        // 邻接窗口 [06:00, 次日) 端点相接不算重叠，06:00 观测只在新窗口计量一次
        ctx("calc2");
        TouCalcResult adjacent = calc(point.getId(), "2026-09-11T06:00:00", "2026-09-12T06:00:00");
        assertEquals(1, adjacent.getObservationCount());
        assertEquals(SegmentType.FLAT.name(), details(adjacent.getId()).get(0).getSegmentType());
        // 同一条 06:00 观测没有在两个窗口重复落明细
        Long obsAtSix = observationMapper.selectOne(new QueryWrapper<WellheadObservation>()
                .eq("observed_at", LocalDateTime.of(2026, 9, 11, 6, 0)), false).getId();
        Long appearances = detailMapper.selectCount(new QueryWrapper<TouCalcDetail>()
                .eq("observation_id", obsAtSix));
        assertEquals(1L, appearances);
    }

    @Test
    void concurrentRecalculate_producesSingleResultAndAudit() throws Exception {
        MonitorPoint point = createPointWithChain("Asia/Shanghai");
        TouRuleChain chain = chainMapper.selectOne(new QueryWrapper<TouRuleChain>()
                .eq("target_id", point.getId()), false);
        Long v1 = newVersion(chain.getId(), null, LocalDateTime.of(2026, 9, 10, 0, 0),
                "1.200", standardIntervals());
        enable(v1);
        obs(point, "2026-09-10T22:30:00", "1.000", "100");
        obs(point, "2026-09-11T07:00:00", "1.000", "100");

        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Long> resultIds = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    RequestContext.set(new RequestContext.Ctx(
                            "REQ-CONCUR-" + suffix + "-" + idx, 2002L, "并发核算员", "Asia/Shanghai"));
                    ready.countDown();
                    start.await();
                    TouCalcResult r = calc(point.getId(), "2026-09-10T22:00:00", "2026-09-12T00:00:00");
                    resultIds.add(r.getId());
                } catch (Exception ex) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                    RequestContext.clear();
                }
            });
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(0, errors.get(), "并发重算不应抛出未处理异常（幂等返回）");
        assertEquals(threads, resultIds.size());
        // 所有线程拿到同一个结果
        assertEquals(1, resultIds.stream().distinct().count());
        // 数据库只有一份结果头、每观测一条明细、一条 CALCULATE 审计
        TouCalcRequest probe = new TouCalcRequest();
        probe.setTargetType(TouTargetType.MONITOR_POINT.name());
        probe.setTargetId(point.getId());
        probe.setWindowStart(LocalDateTime.of(2026, 9, 10, 22, 0));
        probe.setWindowEnd(LocalDateTime.of(2026, 9, 12, 0, 0));
        assertEquals(1, resultMapper.selectCount(new QueryWrapper<TouCalcResult>()
                .eq("target_id", point.getId())
                .eq("window_start", probe.getWindowStart())
                .eq("window_end", probe.getWindowEnd())));
        assertEquals(2, detailMapper.selectCount(new QueryWrapper<TouCalcDetail>()
                .eq("calc_result_id", resultIds.get(0))));
        Long calcAudits = auditMapper.selectCount(new QueryWrapper<BizWriteAudit>()
                .eq("object_type", "TOU_CALC_RESULT")
                .eq("action", "CALCULATE")
                .eq("object_id", resultIds.get(0)));
        assertEquals(1L, calcAudits);
    }

    // ============================ 业务时区：井场时区贯穿试验窗口与结果 ============================

    @Test
    void wellSiteTimezone_isCarriedByWindowAndClassifications() {
        MonitorPoint point = createPointWithChain("Asia/Urumqi");
        TouRuleChain chain = chainMapper.selectOne(new QueryWrapper<TouRuleChain>()
                .eq("target_id", point.getId()), false);
        Long v1 = newVersion(chain.getId(), null, LocalDateTime.of(2026, 9, 10, 0, 0),
                "1.200", standardIntervals());
        enable(v1);
        // 乌鲁木齐当地挂钟 02:00（跨午夜谷段），按井场时区归入前一日 22:00 启动实例
        obs(point, "2026-09-11T02:00:00", "1.000", "100");

        ctx("calc-urumqi");
        TouCalcResult result = calc(point.getId(), "2026-09-10T22:00:00", "2026-09-11T08:00:00");
        assertEquals("Asia/Urumqi", result.getWindowTimezone());
        TouCalcDetail d = details(result.getId()).get(0);
        assertEquals(SegmentType.VALLEY.name(), d.getSegmentType());
        assertEquals(-1, d.getDayOffset());
        assertEquals(java.time.LocalDate.of(2026, 9, 10), d.getBizDate());
        assertTrue(d.getDetailSnapshot().contains("Asia/Urumqi"));
    }
}
