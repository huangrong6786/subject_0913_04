package com.evops.geothermal.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BizException;
import com.evops.common.RequestContext;
import com.evops.geothermal.dto.TouChainCreateRequest;
import com.evops.geothermal.dto.TouIntervalUpdateRequest;
import com.evops.geothermal.dto.TouVersionCloneRequest;
import com.evops.geothermal.dto.TouVersionDraftRequest;
import com.evops.geothermal.entity.MonitorPoint;
import com.evops.geothermal.entity.TestSection;
import com.evops.geothermal.entity.TouInterval;
import com.evops.geothermal.entity.TouRuleChain;
import com.evops.geothermal.entity.TouRuleSet;
import com.evops.geothermal.entity.WellGroup;
import com.evops.geothermal.enums.SegmentType;
import com.evops.geothermal.enums.TouRuleStatus;
import com.evops.geothermal.enums.TouTargetType;
import com.evops.geothermal.mapper.MonitorPointMapper;
import com.evops.geothermal.mapper.TestSectionMapper;
import com.evops.geothermal.mapper.TouIntervalMapper;
import com.evops.geothermal.mapper.TouRuleChainMapper;
import com.evops.geothermal.mapper.TouRuleSetMapper;
import com.evops.geothermal.mapper.WellGroupMapper;
import com.evops.geothermal.security.CurrentAccount;
import com.evops.geothermal.tou.DailyIntervalSpec;
import com.evops.geothermal.tou.DailyTimeline;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 井口监测时序规则维护服务（峰 / 平 / 谷版本化规则链）。
 *
 * <ul>
 *   <li>每个监测对象（监测点 / 井组）一条规则链，链上版本号递增；</li>
 *   <li>版本只在 DRAFT 状态可维护区间；一旦 ENABLED 立即冻结（frozen_snapshot + 区间行均不可改），
 *       业务变更只能基于历史版本开新草稿并重新启用；</li>
 *   <li>启用校验：区间边界合法、编码不重复、两两不重叠（左闭右开）、完整覆盖 1440 分钟、
 *       峰/平/谷三类齐备；</li>
 *   <li>新版本启用时把上一版本生效窗口在新版本起点处闭合 [.., effectiveFrom)（左闭右开），
 *       旧版本置 SUPERSEDED，历史计算永远引用当时版本；</li>
 *   <li>同链并发启用由规则链乐观锁串行化，失败方回滚重试。</li>
 * </ul>
 */
@Service
public class TouRuleService {

    private final TouRuleChainMapper chainMapper;
    private final TouRuleSetMapper setMapper;
    private final TouIntervalMapper intervalMapper;
    private final WellGroupMapper groupMapper;
    private final TestSectionMapper sectionMapper;
    private final MonitorPointMapper pointMapper;
    private final AuditService auditService;
    private final CurrentAccount currentAccount;

    public TouRuleService(TouRuleChainMapper chainMapper,
                          TouRuleSetMapper setMapper,
                          TouIntervalMapper intervalMapper,
                          WellGroupMapper groupMapper,
                          TestSectionMapper sectionMapper,
                          MonitorPointMapper pointMapper,
                          AuditService auditService,
                          CurrentAccount currentAccount) {
        this.chainMapper = chainMapper;
        this.setMapper = setMapper;
        this.intervalMapper = intervalMapper;
        this.groupMapper = groupMapper;
        this.sectionMapper = sectionMapper;
        this.pointMapper = pointMapper;
        this.auditService = auditService;
        this.currentAccount = currentAccount;
    }

    private long tenantId() {
        return currentAccount.get().getTenantId();
    }

    /** 规则维护是运营写操作：仅租户管理员；TENANT_VIEWER 只读账号拒绝。 */
    private void assertCanWrite() {
        if (!currentAccount.get().isTenantAdmin()) {
            throw new BizException("FORBIDDEN", "只读账号不能维护时序规则");
        }
    }

    // ============================ 规则链 ============================

    @Transactional
    public TouRuleChain createChain(TouChainCreateRequest req) {
        assertCanWrite();
        TouTargetType targetType = parseTargetType(req.getTargetType());
        assertTargetExists(targetType, req.getTargetId());

        if (chainMapper.selectCount(new QueryWrapper<TouRuleChain>()
                .eq("tenant_id", tenantId()).eq("chain_code", req.getChainCode())) > 0) {
            throw new BizException("DUPLICATE_KEY", "规则链编码已存在: " + req.getChainCode());
        }
        if (chainMapper.selectCount(new QueryWrapper<TouRuleChain>()
                .eq("tenant_id", tenantId())
                .eq("target_type", targetType.name())
                .eq("target_id", req.getTargetId())) > 0) {
            throw new BizException("DUPLICATE_KEY", "该对象已存在规则链，一个对象只能维护一条规则链");
        }

        TouRuleChain chain = new TouRuleChain();
        chain.setTenantId(tenantId());
        chain.setChainCode(req.getChainCode());
        chain.setChainName(req.getChainName());
        chain.setTargetType(targetType.name());
        chain.setTargetId(req.getTargetId());
        chain.setStatus("ACTIVE");
        chainMapper.insert(chain);
        auditService.record("TOU_RULE_CHAIN", chain.getId(), "CREATE", AuditService.snapshot(
                "chainCode", chain.getChainCode(), "targetType", targetType.name(),
                "targetId", req.getTargetId(), "version", chain.getVersion()));
        return chain;
    }

    public List<TouRuleChain> listChains(String targetType, Long targetId) {
        QueryWrapper<TouRuleChain> qw = new QueryWrapper<TouRuleChain>()
                .eq("tenant_id", tenantId());
        if (targetType != null && !targetType.trim().isEmpty()) {
            TouTargetType tt = parseTargetType(targetType);
            qw.eq("target_type", tt.name());
        }
        if (targetId != null) {
            qw.eq("target_id", targetId);
        }
        // 只读账号：仅可见授权井组（含其下监测点）的规则链
        if (!currentAccount.get().isTenantAdmin()) {
            String grantedGroups = grantedGroupIdsSql();
            qw.and(w -> w.eq("target_type", TouTargetType.WELL_GROUP.name())
                    .inSql("target_id", grantedGroups)
                    .or(o -> o.eq("target_type", TouTargetType.MONITOR_POINT.name())
                            .inSql("target_id",
                                    "SELECT p.id FROM t_monitor_point p JOIN t_test_section s ON p.test_section_id = s.id "
                                            + "WHERE s.well_group_id IN (" + grantedGroups + ")")));
        }
        return chainMapper.selectList(qw.orderByAsc("chain_code"));
    }

    /** 只读账号对象授权校验：井组直接授权；监测点沿试验段→井组收敛。谓词下推 SQL，无法被入参绕过。 */
    private void assertCanReadChain(TouRuleChain chain) {
        if (currentAccount.get().isTenantAdmin()) {
            return;
        }
        TouTargetType targetType = TouTargetType.valueOf(chain.getTargetType());
        String granted = grantedGroupIdsSql();
        Long count;
        if (targetType == TouTargetType.WELL_GROUP) {
            count = groupMapper.selectCount(new QueryWrapper<WellGroup>()
                    .eq("id", chain.getTargetId()).eq("tenant_id", tenantId())
                    .inSql("id", granted));
        } else {
            count = pointMapper.selectCount(new QueryWrapper<MonitorPoint>()
                    .eq("id", chain.getTargetId()).eq("tenant_id", tenantId())
                    .inSql("test_section_id",
                            "SELECT s.id FROM t_test_section s WHERE s.well_group_id IN (" + granted + ")"));
        }
        if (count == null || count == 0) {
            throw new BizException("FORBIDDEN", "对象未授权，不能访问该规则链");
        }
    }

    private String grantedGroupIdsSql() {
        String account = currentAccount.get().getAccount().replace("'", "''");
        return "SELECT object_id FROM t_object_grant WHERE tenant_id = " + tenantId()
                + " AND grantee_account = '" + account + "' AND object_type = 'WELL_GROUP'";
    }

    // ============================ 草稿版本 ============================

    /** 新建草稿版本（链上版本号自动递增），区间在启用前可继续增删改。 */
    @Transactional
    public TouRuleSet createDraft(Long chainId, TouVersionDraftRequest req) {
        assertCanWrite();
        TouRuleChain chain = requireChain(chainId);
        if (setMapper.selectCount(new QueryWrapper<TouRuleSet>()
                .eq("chain_id", chainId).eq("status", TouRuleStatus.DRAFT.name())) > 0) {
            throw new BizException("DRAFT_EXISTS", "该规则链已存在草稿版本，请先启用或删除草稿");
        }
        assertEffectiveFrom(req.getEffectiveFrom());
        Integer maxVersion = setMapper.selectObjs(new QueryWrapper<TouRuleSet>()
                .select("COALESCE(MAX(version_no), 0) AS v").eq("chain_id", chainId))
                .stream().findFirst().map(o -> ((Number) o).intValue()).orElse(0);

        TouRuleSet set = new TouRuleSet();
        set.setTenantId(tenantId());
        set.setChainId(chainId);
        set.setVersionNo(maxVersion + 1);
        set.setStatus(TouRuleStatus.DRAFT.name());
        set.setEffectiveFrom(req.getEffectiveFrom());
        set.setEffectiveTo(null);
        set.setPressureThresholdMpa(req.getPressureThresholdMpa());
        set.setRemark(req.getRemark());
        setMapper.insert(set);

        int seq = 0;
        for (TouVersionDraftRequest.IntervalItem item : req.getIntervals()) {
            insertIntervalRow(set, item.getIntervalCode(), parseSegment(item.getSegmentType()),
                    item.getStartMinute(), item.getEndMinute(), item.getPriceCoefficient(), seq++);
        }
        // 草稿落库即做边界/重叠/编码校验（不要求全覆盖，允许逐步补齐到启用时）
        validateNoOverlap(loadSpecs(set.getId()));
        auditService.record("TOU_RULE_SET", set.getId(), "DRAFT", AuditService.snapshot(
                "chainId", chainId, "versionNo", set.getVersionNo(),
                "effectiveFrom", String.valueOf(req.getEffectiveFrom()),
                "pressureThresholdMpa", req.getPressureThresholdMpa(),
                "intervalCount", req.getIntervals().size()));
        return set;
    }

    /** 基于任意历史版本（含已启用/已闭合）复制区间开新草稿——规则不能原地修改，只能迭代新版本。 */
    @Transactional
    public TouRuleSet cloneAsDraft(Long sourceSetId, TouVersionCloneRequest req) {
        assertCanWrite();
        TouRuleSet source = requireSet(sourceSetId);
        TouRuleChain chain = requireChain(source.getChainId());
        if (TouRuleStatus.DRAFT.name().equals(source.getStatus())) {
            throw new BizException("INVALID_STATE", "草稿版本无需复制，可直接维护后启用");
        }
        if (req.getEffectiveFrom() == null) {
            throw new BizException("VALIDATION", "开新版本必须显式指定 effectiveFrom（生效起点，含）");
        }
        if (setMapper.selectCount(new QueryWrapper<TouRuleSet>()
                .eq("chain_id", chain.getId()).eq("status", TouRuleStatus.DRAFT.name())) > 0) {
            throw new BizException("DRAFT_EXISTS", "该规则链已存在草稿版本，请先启用或删除草稿");
        }
        Integer maxVersion = setMapper.selectObjs(new QueryWrapper<TouRuleSet>()
                .select("COALESCE(MAX(version_no), 0) AS v").eq("chain_id", chain.getId()))
                .stream().findFirst().map(o -> ((Number) o).intValue()).orElse(0);
        LocalDateTime effectiveFrom = req.getEffectiveFrom();
        assertEffectiveFrom(effectiveFrom);
        TouRuleSet current = chain.getCurrentVersionId() == null ? null
                : setMapper.selectById(chain.getCurrentVersionId());
        if (current != null && !effectiveFrom.isAfter(current.getEffectiveFrom())) {
            throw new BizException("VALIDATION",
                    "新版本生效起点必须晚于当前生效版本的起点: " + current.getEffectiveFrom());
        }

        TouRuleSet set = new TouRuleSet();
        set.setTenantId(tenantId());
        set.setChainId(chain.getId());
        set.setVersionNo(maxVersion + 1);
        set.setStatus(TouRuleStatus.DRAFT.name());
        set.setEffectiveFrom(effectiveFrom);
        set.setPressureThresholdMpa(req.getPressureThresholdMpa() != null
                ? req.getPressureThresholdMpa() : source.getPressureThresholdMpa());
        set.setRemark(req.getRemark());
        setMapper.insert(set);

        List<TouInterval> sourceIntervals = listIntervalRows(sourceSetId);
        int seq = 0;
        for (TouInterval src : sourceIntervals) {
            insertIntervalRow(set, src.getIntervalCode(), parseSegment(src.getSegmentType()),
                    src.getStartMinute(), src.getEndMinute(), src.getPriceCoefficient(), seq++);
        }
        auditService.record("TOU_RULE_SET", set.getId(), "CLONE_DRAFT", AuditService.snapshot(
                "chainId", chain.getId(), "versionNo", set.getVersionNo(),
                "sourceSetId", sourceSetId, "sourceVersionNo", source.getVersionNo(),
                "effectiveFrom", String.valueOf(effectiveFrom)));
        return set;
    }

    /** 删除草稿（仅 DRAFT；启用后版本永久保留）。 */
    @Transactional
    public void deleteDraft(Long setId) {
        assertCanWrite();
        TouRuleSet set = requireSet(setId);
        if (TouRuleStatus.DRAFT.name().equals(set.getStatus())) {
            intervalMapper.delete(new QueryWrapper<TouInterval>().eq("rule_set_id", setId));
            setMapper.deleteById(setId);
            auditService.record("TOU_RULE_SET", setId, "DELETE_DRAFT", AuditService.snapshot(
                    "chainId", set.getChainId(), "versionNo", set.getVersionNo()));
        } else {
            throw new BizException("FROZEN_RULE", "规则版本已启用，不能删除（历史结果可能引用该版本）");
        }
    }

    // ============================ 草稿区间维护 ============================

    @Transactional
    public TouInterval addInterval(Long setId, TouIntervalUpdateRequest req, String intervalCode) {
        assertCanWrite();
        TouRuleSet set = requireDraftSet(setId);
        if (intervalCode == null || intervalCode.trim().isEmpty()) {
            throw new BizException("VALIDATION", "必须提供 intervalCode");
        }
        if (intervalMapper.selectCount(new QueryWrapper<TouInterval>()
                .eq("rule_set_id", setId).eq("interval_code", intervalCode)) > 0) {
            throw new BizException("DUPLICATE_KEY", "区间编码在本版本已存在: " + intervalCode);
        }
        int seq = listIntervalRows(setId).size();
        TouInterval interval = insertIntervalRow(set, intervalCode, parseSegment(req.getSegmentType()),
                req.getStartMinute(), req.getEndMinute(), req.getPriceCoefficient(), seq);
        validateNoOverlap(loadSpecs(setId));
        auditService.record("TOU_INTERVAL", interval.getId(), "ADD", AuditService.snapshot(
                "ruleSetId", setId, "versionNo", set.getVersionNo(), "intervalCode", intervalCode,
                "startMinute", req.getStartMinute(), "endMinute", req.getEndMinute()));
        return interval;
    }

    @Transactional
    public TouInterval updateInterval(Long setId, String intervalCode, TouIntervalUpdateRequest req) {
        assertCanWrite();
        TouRuleSet set = requireDraftSet(setId);
        TouInterval interval = intervalMapper.selectOne(new QueryWrapper<TouInterval>()
                .eq("rule_set_id", setId).eq("interval_code", intervalCode), false);
        if (interval == null) {
            throw new BizException("NOT_FOUND", "区间不存在: " + intervalCode);
        }
        DailyTimeline.validateBounds(req.getStartMinute(), req.getEndMinute());
        interval.setSegmentType(parseSegment(req.getSegmentType()).name());
        interval.setStartMinute(req.getStartMinute());
        interval.setEndMinute(req.getEndMinute());
        interval.setPriceCoefficient(req.getPriceCoefficient());
        intervalMapper.updateById(interval);
        validateNoOverlap(loadSpecs(setId));
        auditService.record("TOU_INTERVAL", interval.getId(), "UPDATE", AuditService.snapshot(
                "ruleSetId", setId, "versionNo", set.getVersionNo(), "intervalCode", intervalCode,
                "startMinute", req.getStartMinute(), "endMinute", req.getEndMinute()));
        return interval;
    }

    @Transactional
    public void deleteInterval(Long setId, String intervalCode) {
        assertCanWrite();
        TouRuleSet set = requireDraftSet(setId);
        TouInterval interval = intervalMapper.selectOne(new QueryWrapper<TouInterval>()
                .eq("rule_set_id", setId).eq("interval_code", intervalCode), false);
        if (interval == null) {
            throw new BizException("NOT_FOUND", "区间不存在: " + intervalCode);
        }
        intervalMapper.deleteById(interval.getId());
        auditService.record("TOU_INTERVAL", interval.getId(), "DELETE", AuditService.snapshot(
                "ruleSetId", setId, "versionNo", set.getVersionNo(), "intervalCode", intervalCode));
    }

    // ============================ 启用冻结 ============================

    /**
     * 草稿 → 启用：完整校验后冻结快照；闭合上一版本生效窗口并切换链当前版本。
     * 规则链乐观锁保证同链并发启用只成功一个。
     */
    @Transactional
    public TouRuleSet enableVersion(Long setId) {
        assertCanWrite();
        TouRuleSet set = requireSet(setId);
        if (!TouRuleStatus.DRAFT.name().equals(set.getStatus())) {
            throw new BizException("FROZEN_RULE",
                    "规则版本已是 " + set.getStatus() + " 状态，启用后不能原地修改或重复启用");
        }
        TouRuleChain chain = requireChain(set.getChainId());
        List<DailyIntervalSpec> specs = loadSpecs(setId);
        // 困难级约束：重叠拒绝 + 全覆盖 + 至少峰平谷三类
        DailyTimeline.validateCoverage(specs);
        assertThreeSegments(specs);
        assertEffectiveFrom(set.getEffectiveFrom());

        TouRuleSet previous = chain.getCurrentVersionId() == null ? null
                : setMapper.selectById(chain.getCurrentVersionId());
        if (previous != null) {
            if (!set.getEffectiveFrom().isAfter(previous.getEffectiveFrom())) {
                throw new BizException("VALIDATION",
                        "新版本生效起点必须晚于当前生效版本的起点: " + previous.getEffectiveFrom());
            }
            // 左闭右开闭合：上一版本生效窗口为 [previousFrom, newFrom)
            previous.setStatus(TouRuleStatus.SUPERSEDED.name());
            previous.setEffectiveTo(set.getEffectiveFrom());
            if (setMapper.updateById(previous) == 0) {
                throw new BizException("CONCURRENT_UPDATE", "规则版本被并发更新，请刷新后重试");
            }
        }

        set.setStatus(TouRuleStatus.ENABLED.name());
        set.setFreezeTime(LocalDateTime.now());
        set.setFreezeBy(RequestContext.currentOperatorId());
        set.setFrozenSnapshot(auditService.toJson(buildFrozenSnapshot(set, specs)));
        if (setMapper.updateById(set) == 0) {
            throw new BizException("CONCURRENT_UPDATE", "规则版本被并发更新，请刷新后重试");
        }

        chain.setCurrentVersionId(set.getId());
        if (chainMapper.updateById(chain) == 0) {
            throw new BizException("CONCURRENT_UPDATE", "规则链被并发启用，请刷新后重试");
        }

        auditService.record("TOU_RULE_SET", set.getId(), "ENABLE", AuditService.snapshot(
                "chainId", chain.getId(), "versionNo", set.getVersionNo(),
                "effectiveFrom", String.valueOf(set.getEffectiveFrom()),
                "supersededSetId", previous == null ? null : previous.getId(),
                "pressureThresholdMpa", set.getPressureThresholdMpa(),
                "intervals", specs.size(), "segmentTypes", segmentTypeNames(specs)));
        return setMapper.selectById(setId);
    }

    // ============================ 查询 ============================

    public TouRuleChain getChain(Long chainId) {
        return requireChain(chainId);
    }

    public List<TouRuleSet> listVersions(Long chainId) {
        requireChain(chainId);
        return setMapper.selectList(new QueryWrapper<TouRuleSet>()
                .eq("chain_id", chainId).orderByAsc("version_no"));
    }

    public Map<String, Object> getVersionDetail(Long setId) {
        TouRuleSet set = requireSet(setId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("set", set);
        view.put("intervals", listIntervalRows(setId));
        return view;
    }

    public List<TouInterval> listIntervalRows(Long setId) {
        return intervalMapper.selectList(new QueryWrapper<TouInterval>()
                .eq("rule_set_id", setId).orderByAsc("seq_no", "id"));
    }

    // ============================ 计算引擎共享的内部能力 ============================

    /** 读取版本区间为不可变规格（已按 seq 排序）。 */
    public List<DailyIntervalSpec> loadSpecs(Long setId) {
        List<DailyIntervalSpec> specs = new ArrayList<>();
        for (TouInterval iv : listIntervalRows(setId)) {
            specs.add(new DailyIntervalSpec(iv.getIntervalCode(), iv.getSegmentType(),
                    iv.getStartMinute(), iv.getEndMinute(), iv.getPriceCoefficient()));
        }
        return specs;
    }

    /**
     * 按观测时刻选取当时生效版本：生效窗口 [effectiveFrom, effectiveTo) 左闭右开，
     * effectiveTo 为 null 表示开放区间。版本均已冻结，调用方拿到的即“当时快照”。
     */
    public TouRuleSet resolveEffectiveVersion(Long chainId, LocalDateTime time) {
        List<TouRuleSet> sets = setMapper.selectList(new QueryWrapper<TouRuleSet>()
                .eq("chain_id", chainId)
                .in("status", TouRuleStatus.ENABLED.name(), TouRuleStatus.SUPERSEDED.name())
                .le("effective_from", time)
                .and(w -> w.isNull("effective_to").or().gt("effective_to", time))
                .orderByDesc("effective_from"));
        if (sets.isEmpty()) {
            return null;
        }
        return sets.get(0);
    }

    /** 井场时区解析：监测点 → 试验段 → 井组；井组链直接取井组。 */
    public String resolveTimezone(TouTargetType targetType, Long targetId) {
        WellGroup group;
        if (targetType == TouTargetType.WELL_GROUP) {
            group = groupMapper.selectById(targetId);
        } else {
            MonitorPoint point = pointMapper.selectById(targetId);
            if (point == null) {
                throw new BizException("NOT_FOUND", "监测点不存在: " + targetId);
            }
            TestSection section = sectionMapper.selectById(point.getTestSectionId());
            if (section == null) {
                throw new BizException("NOT_FOUND", "试验段不存在: " + point.getTestSectionId());
            }
            group = groupMapper.selectById(section.getWellGroupId());
        }
        if (group == null) {
            throw new BizException("NOT_FOUND", "井组不存在，无法解析井场时区");
        }
        return group.getTimezone() == null ? "Asia/Shanghai" : group.getTimezone();
    }

    /** 构造启用冻结快照（JSON 内容），计算结果引用它作为当时规则快照。 */
    Map<String, Object> buildFrozenSnapshot(TouRuleSet set, List<DailyIntervalSpec> specs) {
        List<Map<String, Object>> intervalList = new ArrayList<>();
        for (DailyIntervalSpec s : specs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("intervalCode", s.getIntervalCode());
            m.put("segmentType", s.getSegmentType());
            m.put("startMinute", s.getStartMinute());
            m.put("endMinute", s.getEndMinute());
            m.put("priceCoefficient", s.getPriceCoefficient().toPlainString());
            intervalList.add(m);
        }
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("versionNo", set.getVersionNo());
        snap.put("effectiveFrom", String.valueOf(set.getEffectiveFrom()));
        snap.put("pressureThresholdMpa", set.getPressureThresholdMpa().toPlainString());
        snap.put("intervals", intervalList);
        return snap;
    }

    // ============================ private helpers ============================

    private TouInterval insertIntervalRow(TouRuleSet set, String code, SegmentType segment,
                                          int start, int end, BigDecimal coefficient, int seq) {
        DailyTimeline.validateBounds(start, end);
        TouInterval iv = new TouInterval();
        iv.setTenantId(tenantId());
        iv.setRuleSetId(set.getId());
        iv.setIntervalCode(code);
        iv.setSegmentType(segment.name());
        iv.setStartMinute(start);
        iv.setEndMinute(end);
        iv.setPriceCoefficient(coefficient);
        iv.setSeqNo(seq);
        intervalMapper.insert(iv);
        return iv;
    }

    private void validateNoOverlap(List<DailyIntervalSpec> specs) {
        for (int i = 0; i < specs.size(); i++) {
            for (int j = 0; j < i; j++) {
                if (specs.get(i).getIntervalCode().equals(specs.get(j).getIntervalCode())) {
                    throw new BizException("INTERVAL_CODE_DUPLICATED",
                            "区间编码在同一版本内重复: " + specs.get(i).getIntervalCode());
                }
                if (DailyTimeline.overlaps(specs.get(i), specs.get(j))) {
                    throw new BizException("INTERVAL_OVERLAP",
                            "规则区间重叠被拒绝: " + specs.get(i).getIntervalCode()
                                    + " 与 " + specs.get(j).getIntervalCode());
                }
            }
        }
    }

    private void assertThreeSegments(List<DailyIntervalSpec> specs) {
        Set<SegmentType> present = EnumSet.noneOf(SegmentType.class);
        for (DailyIntervalSpec s : specs) {
            present.add(SegmentType.valueOf(s.getSegmentType()));
        }
        if (!present.containsAll(EnumSet.of(SegmentType.PEAK, SegmentType.FLAT, SegmentType.VALLEY))) {
            throw new BizException("SEGMENT_INCOMPLETE",
                    "规则版本必须至少包含峰值(PEAK)、平段(FLAT)、谷值(VALLEY)三类区间");
        }
    }

    private List<String> segmentTypeNames(List<DailyIntervalSpec> specs) {
        List<String> names = new ArrayList<>();
        for (DailyIntervalSpec s : specs) {
            if (!names.contains(s.getSegmentType())) {
                names.add(s.getSegmentType());
            }
        }
        return names;
    }

    private void assertEffectiveFrom(LocalDateTime effectiveFrom) {
        if (effectiveFrom == null) {
            throw new BizException("VALIDATION", "必须提供生效起点 effectiveFrom（含）");
        }
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

    private SegmentType parseSegment(String value) {
        if (value == null) {
            throw new BizException("VALIDATION", "缺少 segmentType");
        }
        try {
            return SegmentType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BizException("VALIDATION", "非法时段类型（仅支持 PEAK/FLAT/VALLEY）: " + value);
        }
    }

    private void assertTargetExists(TouTargetType type, Long targetId) {
        if (type == TouTargetType.WELL_GROUP) {
            WellGroup group = groupMapper.selectById(targetId);
            if (group == null) {
                throw new BizException("NOT_FOUND", "井组不存在: " + targetId);
            }
            if (group.getTenantId() == null || group.getTenantId() != tenantId()) {
                throw new BizException("FORBIDDEN", "不能在其他租户对象上建立规则链");
            }
        } else {
            MonitorPoint point = pointMapper.selectById(targetId);
            if (point == null) {
                throw new BizException("NOT_FOUND", "监测点不存在: " + targetId);
            }
            if (point.getTenantId() == null || point.getTenantId() != tenantId()) {
                throw new BizException("FORBIDDEN", "不能在其他租户对象上建立规则链");
            }
        }
    }

    private TouRuleChain requireChain(Long id) {
        TouRuleChain chain = requireChainEntity(id);
        assertCanReadChain(chain);
        return chain;
    }

    private TouRuleSet requireSet(Long id) {
        TouRuleSet set = setMapper.selectById(id);
        if (set == null) {
            throw new BizException("NOT_FOUND", "规则版本不存在: " + id);
        }
        if (set.getTenantId() == null || set.getTenantId() != tenantId()) {
            throw new BizException("FORBIDDEN", "不能访问其他租户的规则版本");
        }
        assertCanReadChain(requireChainEntity(set.getChainId()));
        return set;
    }

    /** 仅租户校验的规则链读取（供内部装载，不做对象授权；公开入口必须走 requireChain）。 */
    private TouRuleChain requireChainEntity(Long chainId) {
        TouRuleChain chain = chainMapper.selectById(chainId);
        if (chain == null) {
            throw new BizException("NOT_FOUND", "规则链不存在: " + chainId);
        }
        if (chain.getTenantId() == null || chain.getTenantId() != tenantId()) {
            throw new BizException("FORBIDDEN", "不能访问其他租户的规则链");
        }
        return chain;
    }

    private TouRuleSet requireDraftSet(Long setId) {
        TouRuleSet set = requireSet(setId);
        if (!TouRuleStatus.DRAFT.name().equals(set.getStatus())) {
            throw new BizException("FROZEN_RULE",
                    "规则版本已启用（" + set.getStatus() + "），不能原地修改；请基于该版本开新草稿迭代");
        }
        requireChain(set.getChainId());
        return set;
    }
}
