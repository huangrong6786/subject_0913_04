package com.evops.geothermal.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BizException;
import com.evops.geothermal.dto.TouCalcRequest;
import com.evops.geothermal.entity.MonitorPoint;
import com.evops.geothermal.entity.TestSection;
import com.evops.geothermal.entity.TouCalcDetail;
import com.evops.geothermal.entity.TouCalcResult;
import com.evops.geothermal.entity.TouRuleChain;
import com.evops.geothermal.entity.TouRuleSet;
import com.evops.geothermal.entity.WellGroup;
import com.evops.geothermal.entity.WellheadObservation;
import com.evops.geothermal.enums.TouTargetType;
import com.evops.geothermal.mapper.MonitorPointMapper;
import com.evops.geothermal.mapper.TestSectionMapper;
import com.evops.geothermal.mapper.TouCalcDetailMapper;
import com.evops.geothermal.mapper.TouCalcResultMapper;
import com.evops.geothermal.mapper.TouRuleChainMapper;
import com.evops.geothermal.mapper.WellGroupMapper;
import com.evops.geothermal.mapper.WellheadObservationMapper;
import com.evops.geothermal.security.CurrentAccount;
import com.evops.geothermal.tou.DailyIntervalSpec;
import com.evops.geothermal.tou.DailyTimeline;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 试验窗口时序规则计算服务。
 *
 * <ul>
 *   <li><b>井场时区左闭右开</b>：试验窗口 [windowStart, windowEnd) 与观测归类均按
 *       {@code t_well_group.timezone} 的挂钟时间解释；窗口终点时刻的观测不计入本窗口。</li>
 *   <li><b>跨午夜班次不重复计量</b>：跨午夜区间的早段观测（如 00:30）归入前一日启动的区间实例
 *       （dayOffset=-1），每条观测在一次计算中只落一条明细；已算窗口与新窗口相交即拒绝。</li>
 *   <li><b>压力阈值按生效时间选版本</b>：逐条观测按 observedAt 选取当时 ENABLED/SUPERSEDED 版本，
 *       版本冻结快照（区间/系数/阈值）原样写入结果头与明细，历史结果永远读当时快照。</li>
 *   <li><b>BigDecimal 精确累计、最终统一舍入</b>：回灌量 × 时段系数全程 BigDecimal 精确值，
 *       仅在合计最后一步统一 HALF_UP 保留 3 位。</li>
 *   <li><b>并发重算只有一份</b>：窗口唯一索引 (tenant,target,window_start,window_end) 兜底，
 *       事务内先对规则链行加 FOR UPDATE 锁串行化同对象计算；并发重放幂等返回既有结果。</li>
 * </ul>
 */
@Service
public class TouCalcService {

    private static final Logger log = LoggerFactory.getLogger(TouCalcService.class);
    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    static final int RESULT_SCALE = 3;

    private final TouCalcResultMapper resultMapper;
    private final TouCalcDetailMapper detailMapper;
    private final TouRuleChainMapper chainMapper;
    private final TouRuleService ruleService;
    private final WellheadObservationMapper observationMapper;
    private final WellGroupMapper groupMapper;
    private final TestSectionMapper sectionMapper;
    private final MonitorPointMapper pointMapper;
    private final AuditService auditService;
    private final CurrentAccount currentAccount;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public TouCalcService(TouCalcResultMapper resultMapper,
                          TouCalcDetailMapper detailMapper,
                          TouRuleChainMapper chainMapper,
                          TouRuleService ruleService,
                          WellheadObservationMapper observationMapper,
                          WellGroupMapper groupMapper,
                          TestSectionMapper sectionMapper,
                          MonitorPointMapper pointMapper,
                          AuditService auditService,
                          CurrentAccount currentAccount,
                          PlatformTransactionManager transactionManager,
                          ObjectMapper objectMapper) {
        this.resultMapper = resultMapper;
        this.detailMapper = detailMapper;
        this.chainMapper = chainMapper;
        this.ruleService = ruleService;
        this.observationMapper = observationMapper;
        this.groupMapper = groupMapper;
        this.sectionMapper = sectionMapper;
        this.pointMapper = pointMapper;
        this.auditService = auditService;
        this.currentAccount = currentAccount;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    private long tenantId() {
        return currentAccount.get().getTenantId();
    }

    /** 触发规则计算是运营写操作：仅租户管理员；TENANT_VIEWER 只读账号只能查询历史结果。 */
    private void assertCanCalculate() {
        if (!currentAccount.get().isTenantAdmin()) {
            throw new BizException("FORBIDDEN", "只读账号不能执行规则计算");
        }
    }

    /**
     * 执行（或幂等取回）试验窗口规则计算。
     * 非事务外壳：事务内唯一键冲突时回滚整个事务，外壳捕获后返回既有结果，保证并发重算一份。
     */
    public TouCalcResult calculate(TouCalcRequest req) {
        assertCanCalculate();
        TouTargetType targetType = parseTargetType(req.getTargetType());
        validateWindow(req.getWindowStart(), req.getWindowEnd());
        assertTargetTenant(targetType, req.getTargetId());
        String timezone = ruleService.resolveTimezone(targetType, req.getTargetId());
        TouRuleChain chain = requireChainForTarget(targetType, req.getTargetId());

        // 幂等预检：同一左闭右开窗口已有结果 → 直接返回历史结果（读取当时快照，不重算）
        TouCalcResult existing = findByWindow(targetType, req.getTargetId(),
                req.getWindowStart(), req.getWindowEnd());
        if (existing != null) {
            return existing;
        }
        // 跨午夜班次不重复计量：新窗口不得与任何已算窗口有左闭右开相交
        assertNoOverlappingWindow(targetType, req.getTargetId(),
                req.getWindowStart(), req.getWindowEnd());

        try {
            return transactionTemplate.execute(status ->
                    doCalculate(req, targetType, timezone, chain));
        } catch (DuplicateKeyException ex) {
            // 并发重算：唯一索引兜底，失败者事务已回滚，返回赢家结果
            TouCalcResult winner = findByWindow(targetType, req.getTargetId(),
                    req.getWindowStart(), req.getWindowEnd());
            if (winner != null) {
                log.info("并发重算命中既有结果 resultId={} calcNo={}", winner.getId(), winner.getCalcNo());
                return winner;
            }
            throw ex;
        }
    }

    /** 事务体：链行 FOR UPDATE 串行化 → 取数 → 逐观测版本解析与归类 → 精确累计 → 落头/明细/审计。 */
    private TouCalcResult doCalculate(TouCalcRequest req, TouTargetType targetType,
                                      String timezone, TouRuleChain chain) {
        // 再次持锁校验，杜绝两个并发窗口交错通过预检
        TouRuleChain locked = chainMapper.selectOne(new QueryWrapper<TouRuleChain>()
                .eq("id", chain.getId()).last("FOR UPDATE"), false);
        if (locked == null) {
            throw new BizException("NOT_FOUND", "规则链不存在: " + chain.getId());
        }
        TouCalcResult duplicate = findByWindow(targetType, req.getTargetId(),
                req.getWindowStart(), req.getWindowEnd());
        if (duplicate != null) {
            return duplicate;
        }
        assertNoOverlappingWindow(targetType, req.getTargetId(),
                req.getWindowStart(), req.getWindowEnd());

        List<MonitorPoint> points = resolvePoints(targetType, req.getTargetId());
        if (points.isEmpty()) {
            throw new BizException("NO_POINT", "对象下没有可计算的监测点");
        }
        List<Long> pointIds = new ArrayList<>();
        for (MonitorPoint p : points) {
            pointIds.add(p.getId());
        }
        // 左闭右开取数：observed_at ∈ [windowStart, windowEnd)
        List<WellheadObservation> observations = observationMapper.selectList(
                new QueryWrapper<WellheadObservation>()
                        .eq("tenant_id", tenantId())
                        .in("monitor_point_id", pointIds)
                        .ge("observed_at", req.getWindowStart())
                        .lt("observed_at", req.getWindowEnd())
                        .orderByAsc("observed_at", "id"));
        if (observations.isEmpty()) {
            throw new BizException("NO_OBSERVATION", "试验窗口内没有任何井口观测，无法计算");
        }

        // 版本规格缓存（版本不可变，同版本只装载一次）
        Map<Long, VersionPack> versionCache = new LinkedHashMap<>();
        List<TouCalcDetail> details = new ArrayList<>();
        // 全程精确累加（scale=9），最后一步才舍入
        BigDecimal exactTotal = BigDecimal.ZERO;

        for (WellheadObservation obs : observations) {
            TouRuleSet ruleSet = ruleService.resolveEffectiveVersion(chain.getId(), obs.getObservedAt());
            if (ruleSet == null) {
                throw new BizException("RULE_NOT_EFFECTIVE",
                        "观测时刻 " + obs.getObservedAt() + " 没有生效中的规则版本，无法归类（请检查版本生效时间）");
            }
            VersionPack pack = versionCache.computeIfAbsent(ruleSet.getId(), id -> {
                List<DailyIntervalSpec> specs = ruleService.loadSpecs(id);
                if (specs.isEmpty()) {
                    throw new BizException("RULE_CORRUPT", "生效版本没有区间: setId=" + id);
                }
                return new VersionPack(ruleSet, specs);
            });

            DailyTimeline.Match match = DailyTimeline.match(obs.getObservedAt(), pack.specs);
            DailyIntervalSpec spec = match.getSpec();
            BigDecimal scaled = obs.getInjectionVolumeM3().multiply(spec.getPriceCoefficient());
            exactTotal = exactTotal.add(scaled);

            boolean breached = obs.getPressureMpa().compareTo(ruleSet.getPressureThresholdMpa()) > 0;
            TouCalcDetail detail = new TouCalcDetail();
            detail.setObservationId(obs.getId());
            detail.setObservedAt(obs.getObservedAt());
            detail.setBizDate(obs.getObservedAt().toLocalDate().plusDays(match.getDayOffset()));
            detail.setSegmentType(spec.getSegmentType());
            detail.setIntervalCode(spec.getIntervalCode());
            detail.setStartMinute(spec.getStartMinute());
            detail.setEndMinute(spec.getEndMinute());
            detail.setDayOffset(match.getDayOffset());
            detail.setSegmentStart(match.getSegmentStart());
            detail.setSegmentEnd(match.getSegmentEnd());
            detail.setRuleSetId(ruleSet.getId());
            detail.setRuleVersionNo(ruleSet.getVersionNo());
            detail.setRawInjectionVolumeM3(obs.getInjectionVolumeM3());
            detail.setPriceCoefficient(spec.getPriceCoefficient());
            detail.setScaledInjectionM3(scaled);
            detail.setPressureMpa(obs.getPressureMpa());
            detail.setPressureThresholdMpa(ruleSet.getPressureThresholdMpa());
            detail.setThresholdBreached(breached);
            detail.setDetailSnapshot(auditService.toJson(buildDetailSnapshot(
                    timezone, obs, ruleSet, spec, match, scaled, breached)));
            details.add(detail);
        }

        // 最终步骤统一舍入（仅此一次 HALF_UP）
        BigDecimal roundedTotal = exactTotal.setScale(RESULT_SCALE, RoundingMode.HALF_UP);

        String calcNo = buildCalcNo(targetType, req.getTargetId(),
                req.getWindowStart(), req.getWindowEnd());
        TouCalcResult result = new TouCalcResult();
        result.setTenantId(tenantId());
        result.setCalcNo(calcNo);
        result.setTargetType(targetType.name());
        result.setTargetId(req.getTargetId());
        result.setWindowStart(req.getWindowStart());
        result.setWindowEnd(req.getWindowEnd());
        result.setWindowTimezone(timezone);
        result.setRuleChainId(chain.getId());
        result.setObservationCount(details.size());
        result.setTotalScaledInjectionM3(roundedTotal);
        result.setRoundingScale(RESULT_SCALE);
        result.setRoundingMode(RoundingMode.HALF_UP.name());
        result.setStatus("CALCULATED");
        result.setCalcSnapshot(auditService.toJson(buildCalcSnapshot(chain, timezone, req,
                versionCache, details.size(), exactTotal, roundedTotal)));
        resultMapper.insert(result);

        for (TouCalcDetail d : details) {
            d.setCalcResultId(result.getId());
            detailMapper.insert(d);
        }

        auditService.record("TOU_CALC_RESULT", result.getId(), "CALCULATE", AuditService.snapshot(
                "calcNo", calcNo, "targetType", targetType.name(), "targetId", req.getTargetId(),
                "windowStart", String.valueOf(req.getWindowStart()),
                "windowEnd", String.valueOf(req.getWindowEnd()),
                "timezone", timezone, "observationCount", details.size(),
                "versionCount", versionCache.size(),
                "totalScaledInjectionM3", roundedTotal.toPlainString()));
        return result;
    }

    // ============================ 历史结果读取（只读当时快照） ============================

    public TouCalcResult requireResult(Long id) {
        TouCalcResult result = resultMapper.selectById(id);
        if (result == null) {
            throw new BizException("NOT_FOUND", "计算结果不存在: " + id);
        }
        if (result.getTenantId() == null || result.getTenantId() != tenantId()) {
            throw new BizException("FORBIDDEN", "不能访问其他租户的计算结果");
        }
        assertCanReadTarget(TouTargetType.valueOf(result.getTargetType()), result.getTargetId());
        return result;
    }

    public List<TouCalcResult> listResults(String targetType, Long targetId) {
        QueryWrapper<TouCalcResult> qw = new QueryWrapper<TouCalcResult>()
                .eq("tenant_id", tenantId());
        TouTargetType tt = null;
        if (targetType != null && !targetType.trim().isEmpty()) {
            tt = parseTargetType(targetType);
            qw.eq("target_type", tt.name());
        }
        if (targetId != null) {
            qw.eq("target_id", targetId);
        }
        // 只读账号：只能看到授权井组（含其下监测点）的计算结果
        if (!currentAccount.get().isTenantAdmin()) {
            String grantedGroups = grantedGroupIdsSql();
            qw.and(w -> w.eq("target_type", TouTargetType.WELL_GROUP.name())
                    .inSql("target_id", grantedGroups)
                    .or(o -> o.eq("target_type", TouTargetType.MONITOR_POINT.name())
                            .inSql("target_id",
                                    "SELECT p.id FROM t_monitor_point p JOIN t_test_section s ON p.test_section_id = s.id "
                                            + "WHERE s.well_group_id IN (" + grantedGroups + ")")));
        }
        return resultMapper.selectList(qw.orderByDesc("window_start", "id"));
    }

    /**
     * 结果视图：头 + 明细全部来自落库快照行，不回查现行规则表——
     * 规则后续迭代/新版本启用不改变历史结果展示。
     */
    public Map<String, Object> getResultView(Long id) {
        TouCalcResult result = requireResult(id);
        List<TouCalcDetail> details = detailMapper.selectList(new QueryWrapper<TouCalcDetail>()
                .eq("calc_result_id", id).orderByAsc("observed_at", "id"));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("result", result);
        view.put("details", details);
        // 峰平谷分段合计：从落库精确明细重新 BigDecimal 累加，展示层统一舍入
        Map<String, BigDecimal> segmentExact = new LinkedHashMap<>();
        long breachCount = 0;
        for (TouCalcDetail d : details) {
            segmentExact.merge(d.getSegmentType(), d.getScaledInjectionM3(), BigDecimal::add);
            if (Boolean.TRUE.equals(d.getThresholdBreached())) {
                breachCount++;
            }
        }
        Map<String, String> segmentTotals = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : segmentExact.entrySet()) {
            segmentTotals.put(e.getKey(), e.getValue().setScale(RESULT_SCALE, RoundingMode.HALF_UP).toPlainString());
        }
        view.put("segmentTotalsM3", segmentTotals);
        view.put("thresholdBreachCount", breachCount);
        return view;
    }

    // ============================ private helpers ============================

    /** 只读账号对象授权：井组直接授权；监测点沿试验段→井组收敛。谓词下推 SQL，无法被入参绕过。 */
    private void assertCanReadTarget(TouTargetType targetType, Long targetId) {
        if (currentAccount.get().isTenantAdmin()) {
            return;
        }
        String granted = grantedGroupIdsSql();
        Long count;
        if (targetType == TouTargetType.WELL_GROUP) {
            count = groupMapper.selectCount(new QueryWrapper<WellGroup>()
                    .eq("id", targetId).eq("tenant_id", tenantId())
                    .inSql("id", granted));
        } else {
            count = pointMapper.selectCount(new QueryWrapper<MonitorPoint>()
                    .eq("id", targetId).eq("tenant_id", tenantId())
                    .inSql("test_section_id",
                            "SELECT s.id FROM t_test_section s WHERE s.well_group_id IN (" + granted + ")"));
        }
        if (count == null || count == 0) {
            throw new BizException("FORBIDDEN", "对象未授权，不能读取该计算结果");
        }
    }

    private String grantedGroupIdsSql() {
        String account = currentAccount.get().getAccount().replace("'", "''");
        return "SELECT object_id FROM t_object_grant WHERE tenant_id = " + tenantId()
                + " AND grantee_account = '" + account + "' AND object_type = 'WELL_GROUP'";
    }

    private Map<String, Object> buildCalcSnapshot(TouRuleChain chain, String timezone, TouCalcRequest req,
                                                  Map<Long, VersionPack> versionCache, int observationCount,
                                                  BigDecimal exactTotal, BigDecimal roundedTotal) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("timezone", timezone);
        snap.put("targetType", req.getTargetType());
        snap.put("targetId", req.getTargetId());
        snap.put("windowStart", String.valueOf(req.getWindowStart()));
        snap.put("windowEnd", String.valueOf(req.getWindowEnd()));
        snap.put("windowInterval", "[start,end) 左闭右开");
        snap.put("chain", AuditService.snapshot("id", chain.getId(), "chainCode", chain.getChainCode()));
        // 逐版本冻结快照：原样嵌入启用时冻结 JSON，历史结果不再依赖规则表现值
        Map<String, Object> versions = new LinkedHashMap<>();
        for (VersionPack pack : versionCache.values()) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("setId", pack.ruleSet.getId());
            v.put("versionNo", pack.ruleSet.getVersionNo());
            v.put("effectiveFrom", String.valueOf(pack.ruleSet.getEffectiveFrom()));
            v.put("effectiveTo", pack.ruleSet.getEffectiveTo() == null
                    ? null : String.valueOf(pack.ruleSet.getEffectiveTo()));
            v.put("frozenRule", parseJson(pack.ruleSet.getFrozenSnapshot()));
            versions.put(String.valueOf(pack.ruleSet.getId()), v);
        }
        snap.put("versions", versions);
        snap.put("observationCount", observationCount);
        snap.put("exactTotalBeforeRounding", exactTotal.toPlainString());
        snap.put("rounding", AuditService.snapshot("scale", RESULT_SCALE, "mode", RoundingMode.HALF_UP.name(),
                "step", "仅最终合计统一舍入"));
        snap.put("totalScaledInjectionM3", roundedTotal.toPlainString());
        return snap;
    }

    private Map<String, Object> buildDetailSnapshot(String timezone, WellheadObservation obs,
                                                    TouRuleSet ruleSet, DailyIntervalSpec spec,
                                                    DailyTimeline.Match match, BigDecimal scaled,
                                                    boolean breached) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("timezone", timezone);
        snap.put("observationId", obs.getId());
        snap.put("serialNo", obs.getSerialNo());
        snap.put("observedAt", String.valueOf(obs.getObservedAt()));
        snap.put("bizDate", String.valueOf(obs.getObservedAt().toLocalDate().plusDays(match.getDayOffset())));
        snap.put("segmentType", spec.getSegmentType());
        snap.put("intervalCode", spec.getIntervalCode());
        snap.put("segmentStart", String.valueOf(match.getSegmentStart()));
        snap.put("segmentEnd", String.valueOf(match.getSegmentEnd()));
        snap.put("dayOffset", match.getDayOffset());
        snap.put("ruleSetId", ruleSet.getId());
        snap.put("ruleVersionNo", ruleSet.getVersionNo());
        snap.put("ruleEffectiveFrom", String.valueOf(ruleSet.getEffectiveFrom()));
        snap.put("ruleEffectiveTo", ruleSet.getEffectiveTo() == null
                ? null : String.valueOf(ruleSet.getEffectiveTo()));
        snap.put("priceCoefficient", spec.getPriceCoefficient().toPlainString());
        snap.put("rawInjectionVolumeM3", obs.getInjectionVolumeM3().toPlainString());
        snap.put("scaledInjectionM3Exact", scaled.toPlainString());
        snap.put("pressureMpa", obs.getPressureMpa().toPlainString());
        snap.put("pressureThresholdMpa", ruleSet.getPressureThresholdMpa().toPlainString());
        snap.put("thresholdBreached", breached);
        return snap;
    }

    private Object parseJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception ex) {
            log.warn("冻结快照解析失败，按原文保留", ex);
            return json;
        }
    }

    private TouCalcResult findByWindow(TouTargetType targetType, Long targetId,
                                       LocalDateTime start, LocalDateTime end) {
        return resultMapper.selectOne(new QueryWrapper<TouCalcResult>()
                .eq("tenant_id", tenantId())
                .eq("target_type", targetType.name())
                .eq("target_id", targetId)
                .eq("window_start", start)
                .eq("window_end", end), false);
    }

    /** 左闭右开相交判定：存在已算窗口 [a,b) 满足 a &lt; end &amp;&amp; start &lt; b 即拒绝。 */
    private void assertNoOverlappingWindow(TouTargetType targetType, Long targetId,
                                           LocalDateTime start, LocalDateTime end) {
        Long count = resultMapper.selectCount(new QueryWrapper<TouCalcResult>()
                .eq("tenant_id", tenantId())
                .eq("target_type", targetType.name())
                .eq("target_id", targetId)
                .lt("window_start", end)
                .gt("window_end", start));
        if (count != null && count > 0) {
            throw new BizException("WINDOW_OVERLAP",
                    "试验窗口与既有计算窗口重叠（左闭右开相交），跨午夜班次不得重复计量: ["
                            + start + "," + end + ")");
        }
    }

    private List<MonitorPoint> resolvePoints(TouTargetType targetType, Long targetId) {
        if (targetType == TouTargetType.MONITOR_POINT) {
            MonitorPoint point = pointMapper.selectById(targetId);
            List<MonitorPoint> list = new ArrayList<>();
            if (point != null && point.getTenantId() != null && point.getTenantId() == tenantId()) {
                list.add(point);
            }
            return list;
        }
        WellGroup group = groupMapper.selectById(targetId);
        if (group == null) {
            throw new BizException("NOT_FOUND", "井组不存在: " + targetId);
        }
        List<TestSection> sections = sectionMapper.selectList(new QueryWrapper<TestSection>()
                .eq("well_group_id", targetId));
        if (sections.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> sectionIds = new ArrayList<>();
        for (TestSection s : sections) {
            sectionIds.add(s.getId());
        }
        return pointMapper.selectList(new QueryWrapper<MonitorPoint>()
                .eq("tenant_id", tenantId()).in("test_section_id", sectionIds));
    }

    private void assertTargetTenant(TouTargetType targetType, Long targetId) {
        Long tenant;
        if (targetType == TouTargetType.WELL_GROUP) {
            WellGroup g = groupMapper.selectById(targetId);
            if (g == null) {
                throw new BizException("NOT_FOUND", "井组不存在: " + targetId);
            }
            tenant = g.getTenantId();
        } else {
            MonitorPoint p = pointMapper.selectById(targetId);
            if (p == null) {
                throw new BizException("NOT_FOUND", "监测点不存在: " + targetId);
            }
            tenant = p.getTenantId();
        }
        if (tenant == null || tenant != tenantId()) {
            throw new BizException("FORBIDDEN", "不能在其他租户对象上执行计算");
        }
    }

    private TouRuleChain requireChainForTarget(TouTargetType targetType, Long targetId) {
        TouRuleChain chain = chainMapper.selectOne(new QueryWrapper<TouRuleChain>()
                .eq("tenant_id", tenantId())
                .eq("target_type", targetType.name())
                .eq("target_id", targetId), false);
        if (chain == null) {
            throw new BizException("NOT_FOUND", "对象尚未建立时序规则链，请先维护并启用峰平谷规则");
        }
        if (chain.getCurrentVersionId() == null) {
            throw new BizException("RULE_NOT_ENABLED", "规则链尚无启用版本，不能计算");
        }
        return chain;
    }

    private void validateWindow(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            throw new BizException("VALIDATION", "试验窗口必须提供 windowStart/windowEnd");
        }
        if (!end.isAfter(start)) {
            throw new BizException("VALIDATION", "试验窗口终点必须晚于起点（左闭右开 [start,end)）");
        }
    }

    private String buildCalcNo(TouTargetType type, Long targetId, LocalDateTime start, LocalDateTime end) {
        return "TOUCALC-" + type.name() + "-" + targetId + "-" + start.format(NO_FMT) + "-" + end.format(NO_FMT);
    }

    private TouTargetType parseTargetType(String value) {
        if (value == null) {
            throw new BizException("VALIDATION", "缺少 targetType");
        }
        try {
            return TouTargetType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BizException("VALIDATION", "非法 targetType（仅支持 MONITOR_POINT/WELL_GROUP）: " + value);
        }
    }

    /** 一次计算中某版本的不可变装载包（版本头 + 区间规格）。 */
    private static final class VersionPack {
        private final TouRuleSet ruleSet;
        private final List<DailyIntervalSpec> specs;

        private VersionPack(TouRuleSet ruleSet, List<DailyIntervalSpec> specs) {
            this.ruleSet = ruleSet;
            this.specs = specs;
        }
    }
}
