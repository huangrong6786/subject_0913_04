package com.evops.geothermal.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BizException;
import com.evops.common.RequestContext;
import com.evops.geothermal.dto.BatchCreateRequest;
import com.evops.geothermal.dto.BatchDetailView;
import com.evops.geothermal.dto.MonitorPointRequest;
import com.evops.geothermal.dto.ReadingSubmitRequest;
import com.evops.geothermal.dto.ShiftRequest;
import com.evops.geothermal.dto.TestSectionRequest;
import com.evops.geothermal.dto.WellGroupRequest;
import com.evops.geothermal.entity.MonitorBatch;
import com.evops.geothermal.entity.MonitorPoint;
import com.evops.geothermal.entity.ReinjectionShift;
import com.evops.geothermal.entity.TestSection;
import com.evops.geothermal.entity.WellGroup;
import com.evops.geothermal.entity.WellheadReading;
import com.evops.geothermal.enums.BatchStatus;
import com.evops.geothermal.enums.ShiftStatus;
import com.evops.geothermal.mapper.MonitorBatchMapper;
import com.evops.geothermal.mapper.MonitorPointMapper;
import com.evops.geothermal.mapper.ReinjectionShiftMapper;
import com.evops.geothermal.mapper.TestSectionMapper;
import com.evops.geothermal.mapper.WellGroupMapper;
import com.evops.geothermal.mapper.WellheadReadingMapper;
import com.evops.geothermal.security.CurrentAccount;
import com.evops.geothermal.security.DataScope;
import com.evops.geothermal.security.QueryScopeApplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 地热井回灌试验与井口监测主服务。
 * 所有跨表写入均在同一事务内完成，并通过 AuditService 落请求号/操作者/业务时区/版本快照。
 */
@Service
public class GeothermalService {

    private final WellGroupMapper groupMapper;
    private final TestSectionMapper sectionMapper;
    private final MonitorPointMapper pointMapper;
    private final ReinjectionShiftMapper shiftMapper;
    private final MonitorBatchMapper batchMapper;
    private final WellheadReadingMapper readingMapper;
    private final AuditService auditService;
    private final CurrentAccount currentAccount;
    private final QueryScopeApplier scopeApplier;

    public GeothermalService(WellGroupMapper groupMapper,
                             TestSectionMapper sectionMapper,
                             MonitorPointMapper pointMapper,
                             ReinjectionShiftMapper shiftMapper,
                             MonitorBatchMapper batchMapper,
                             WellheadReadingMapper readingMapper,
                             AuditService auditService,
                             CurrentAccount currentAccount,
                             QueryScopeApplier scopeApplier) {
        this.groupMapper = groupMapper;
        this.sectionMapper = sectionMapper;
        this.pointMapper = pointMapper;
        this.shiftMapper = shiftMapper;
        this.batchMapper = batchMapper;
        this.readingMapper = readingMapper;
        this.auditService = auditService;
        this.currentAccount = currentAccount;
        this.scopeApplier = scopeApplier;
    }

    /** 写链路租户守卫：只能在本租户对象上操作，下级对象租户从所属上级继承。 */
    private long currentTenantId() {
        return currentAccount.get().getTenantId();
    }

    /** 读链路数据权限范围：租户隔离必带；只读账号再叠加对象授权收敛。 */
    private DataScope currentScope() {
        return new DataScope(currentAccount.get().getTenantId(), currentAccount.get().getAccount(),
                !currentAccount.get().isTenantAdmin());
    }

    private void assertSameTenant(Long resourceTenantId) {
        if (resourceTenantId != null && resourceTenantId.longValue() != currentTenantId()) {
            throw new BizException("FORBIDDEN", "不能操作其他租户的对象");
        }
    }

    private String timezone() {
        RequestContext.Ctx ctx = RequestContext.get();
        String tz = ctx == null ? null : ctx.getBizTimezone();
        if (tz == null || tz.trim().isEmpty()) {
            return "Asia/Shanghai";
        }
        return normalizeTimezone(tz);
    }

    /** 井场时区规范化与合法性校验。 */
    static String normalizeTimezone(String tz) {
        if (tz == null || tz.trim().isEmpty()) {
            return "Asia/Shanghai";
        }
        try {
            return ZoneId.of(tz.trim()).getId();
        } catch (Exception ex) {
            throw new BizException("VALIDATION", "非法井场时区: " + tz);
        }
    }

    // ============================ 井组 ============================

    @Transactional
    public WellGroup createGroup(WellGroupRequest req) {
        if (groupMapper.selectCount(new QueryWrapper<WellGroup>().eq("group_code", req.getGroupCode())) > 0) {
            throw new BizException("DUPLICATE_KEY", "井组编码已存在: " + req.getGroupCode());
        }
        WellGroup group = new WellGroup();
        group.setTenantId(currentTenantId());
        group.setGroupCode(req.getGroupCode());
        group.setGroupName(req.getGroupName());
        group.setLocation(req.getLocation());
        group.setTimezone(normalizeTimezone(req.getTimezone()));
        group.setStatus("ACTIVE");
        groupMapper.insert(group);
        auditService.record("WELL_GROUP", group.getId(), "CREATE",
                AuditService.snapshot("groupCode", group.getGroupCode(), "version", group.getVersion()));
        return group;
    }

    @Transactional
    public void deleteGroup(Long id) {
        WellGroup group = requireGroup(id);
        Long sections = sectionMapper.selectCount(new QueryWrapper<TestSection>().eq("well_group_id", id));
        if (sections > 0) {
            throw new BizException("DELETE_REJECTED", "井组下存在试验段，不能删除: " + group.getGroupCode());
        }
        groupMapper.deleteById(id);
        auditService.record("WELL_GROUP", id, "DELETE",
                AuditService.snapshot("groupCode", group.getGroupCode(), "version", group.getVersion()));
    }

    public List<WellGroup> listGroups() {
        QueryWrapper<WellGroup> qw = new QueryWrapper<>();
        scopeApplier.scopeWellGroupTable(qw, currentScope());
        return groupMapper.selectList(qw.orderByAsc("group_code"));
    }

    // ============================ 试验段 ============================

    @Transactional
    public TestSection createSection(TestSectionRequest req) {
        WellGroup ownerGroup = requireGroup(req.getWellGroupId());
        assertSameTenant(ownerGroup.getTenantId());
        if (sectionMapper.selectCount(new QueryWrapper<TestSection>().eq("section_code", req.getSectionCode())) > 0) {
            throw new BizException("DUPLICATE_KEY", "试验段编码已存在: " + req.getSectionCode());
        }
        TestSection section = new TestSection();
        section.setTenantId(ownerGroup.getTenantId());
        section.setWellGroupId(req.getWellGroupId());
        section.setSectionCode(req.getSectionCode());
        section.setSectionName(req.getSectionName());
        section.setIntervalTopM(req.getIntervalTopM());
        section.setIntervalBottomM(req.getIntervalBottomM());
        section.setStatus("ACTIVE");
        sectionMapper.insert(section);
        auditService.record("TEST_SECTION", section.getId(), "CREATE",
                AuditService.snapshot("sectionCode", section.getSectionCode(),
                        "wellGroupId", section.getWellGroupId(), "version", section.getVersion()));
        return section;
    }

    @Transactional
    public void deleteSection(Long id) {
        TestSection section = requireSection(id);
        if (pointMapper.selectCount(new QueryWrapper<MonitorPoint>().eq("test_section_id", id)) > 0
                || shiftMapper.selectCount(new QueryWrapper<ReinjectionShift>().eq("test_section_id", id)) > 0) {
            throw new BizException("DELETE_REJECTED", "试验段下存在监测点或回灌班次，不能删除: " + section.getSectionCode());
        }
        sectionMapper.deleteById(id);
        auditService.record("TEST_SECTION", id, "DELETE",
                AuditService.snapshot("sectionCode", section.getSectionCode(), "version", section.getVersion()));
    }

    public List<TestSection> listSections(Long groupId) {
        QueryWrapper<TestSection> qw = new QueryWrapper<>();
        scopeApplier.scopeByGroupColumn(qw, currentScope(), "well_group_id");
        if (groupId != null) {
            qw.eq("well_group_id", groupId);
        }
        return sectionMapper.selectList(qw.orderByAsc("section_code"));
    }

    // ============================ 监测点 ============================

    @Transactional
    public MonitorPoint createPoint(MonitorPointRequest req) {
        TestSection ownerSection = requireSection(req.getTestSectionId());
        assertSameTenant(ownerSection.getTenantId());
        if (pointMapper.selectCount(new QueryWrapper<MonitorPoint>().eq("point_code", req.getPointCode())) > 0) {
            throw new BizException("DUPLICATE_KEY", "监测点编码已存在: " + req.getPointCode());
        }
        MonitorPoint point = new MonitorPoint();
        point.setTenantId(ownerSection.getTenantId());
        point.setTestSectionId(req.getTestSectionId());
        point.setPointCode(req.getPointCode());
        point.setPointName(req.getPointName());
        point.setWellName(req.getWellName());
        point.setPointType(req.getPointType() == null ? "WELLHEAD" : req.getPointType());
        point.setStatus("ACTIVE");
        pointMapper.insert(point);
        auditService.record("MONITOR_POINT", point.getId(), "CREATE",
                AuditService.snapshot("pointCode", point.getPointCode(),
                        "testSectionId", point.getTestSectionId(), "version", point.getVersion()));
        return point;
    }

    @Transactional
    public void deletePoint(Long id) {
        MonitorPoint point = requirePoint(id);
        if (batchMapper.selectCount(new QueryWrapper<MonitorBatch>().eq("monitor_point_id", id)) > 0) {
            throw new BizException("DELETE_REJECTED", "监测点下存在监测批次，不能删除: " + point.getPointCode());
        }
        pointMapper.deleteById(id);
        auditService.record("MONITOR_POINT", id, "DELETE",
                AuditService.snapshot("pointCode", point.getPointCode(), "version", point.getVersion()));
    }

    public List<MonitorPoint> listPoints(Long sectionId) {
        QueryWrapper<MonitorPoint> qw = new QueryWrapper<>();
        scopeApplier.scopeBySectionColumn(qw, currentScope(), "test_section_id");
        if (sectionId != null) {
            qw.eq("test_section_id", sectionId);
        }
        return pointMapper.selectList(qw.orderByAsc("point_code"));
    }

    // ============================ 回灌班次 ============================

    @Transactional
    public ReinjectionShift createShift(ShiftRequest req) {
        TestSection section = requireSection(req.getTestSectionId());
        assertSameTenant(section.getTenantId());
        WellGroup group = requireGroup(section.getWellGroupId());
        String shiftCode = buildShiftCode(group.getGroupCode(), section.getSectionCode(),
                req.getShiftDate(), req.getShiftIndex());
        assertShiftUnique(req.getTestSectionId(), req.getShiftDate(), req.getShiftIndex(), shiftCode);

        ReinjectionShift shift = new ReinjectionShift();
        shift.setTenantId(section.getTenantId());
        shift.setTestSectionId(req.getTestSectionId());
        shift.setShiftDate(req.getShiftDate());
        shift.setShiftIndex(req.getShiftIndex());
        shift.setShiftCode(shiftCode);
        shift.setOperatorName(req.getOperatorName());
        shift.setPlannedInjectionM3h(req.getPlannedInjectionM3h());
        shift.setStatus(ShiftStatus.OPEN.name());
        shiftMapper.insert(shift);
        auditService.record("REINJECTION_SHIFT", shift.getId(), "CREATE", AuditService.snapshot(
                "shiftCode", shiftCode, "shiftDate", String.valueOf(req.getShiftDate()),
                "shiftIndex", req.getShiftIndex(), "version", shift.getVersion()));
        return shift;
    }

    /** 状态流转：OPEN -> CLOSED */
    @Transactional
    public ReinjectionShift closeShift(Long id) {
        ReinjectionShift shift = requireShift(id);
        if (ShiftStatus.CLOSED.name().equals(shift.getStatus())) {
            throw new BizException("INVALID_TRANSITION", "班次已闭班，不能重复闭班: " + shift.getShiftCode());
        }
        shift.setStatus(ShiftStatus.CLOSED.name());
        int rows = shiftMapper.updateById(shift);
        if (rows == 0) {
            throw new BizException("CONCURRENT_UPDATE", "班次已被其他请求更新，请刷新后重试");
        }
        auditService.record("REINJECTION_SHIFT", id, "CLOSE",
                AuditService.snapshot("shiftCode", shift.getShiftCode(), "version", shift.getVersion()));
        return shift;
    }

    @Transactional
    public void deleteShift(Long id) {
        ReinjectionShift shift = requireShift(id);
        if (batchMapper.selectCount(new QueryWrapper<MonitorBatch>().eq("shift_id", id)) > 0) {
            throw new BizException("DELETE_REJECTED", "班次下存在监测批次，不能删除: " + shift.getShiftCode());
        }
        shiftMapper.deleteById(id);
        auditService.record("REINJECTION_SHIFT", id, "DELETE",
                AuditService.snapshot("shiftCode", shift.getShiftCode(), "version", shift.getVersion()));
    }

    public List<ReinjectionShift> listShifts(Long sectionId, LocalDate shiftDate) {
        QueryWrapper<ReinjectionShift> qw = new QueryWrapper<>();
        scopeApplier.scopeBySectionColumn(qw, currentScope(), "test_section_id");
        if (sectionId != null) {
            qw.eq("test_section_id", sectionId);
        }
        if (shiftDate != null) {
            qw.eq("shift_date", shiftDate);
        }
        return shiftMapper.selectList(qw.orderByAsc("shift_date", "shift_index"));
    }

    private void assertShiftUnique(Long sectionId, LocalDate date, Integer index, String shiftCode) {
        if (shiftMapper.selectCount(new QueryWrapper<ReinjectionShift>().eq("shift_code", shiftCode)) > 0) {
            throw new BizException("DUPLICATE_KEY", "班次复合键已存在: " + shiftCode);
        }
        if (shiftMapper.selectCount(new QueryWrapper<ReinjectionShift>()
                .eq("test_section_id", sectionId)
                .eq("shift_date", date)
                .eq("shift_index", index)) > 0) {
            throw new BizException("DUPLICATE_KEY",
                    "同试验段/业务日期/班次序号的班次已存在: " + sectionId + "/" + date + "/" + index);
        }
    }

    static String buildShiftCode(String groupCode, String sectionCode, LocalDate date, Integer index) {
        return groupCode + "-" + sectionCode + "-" + date + "-S" + index;
    }

    // ============================ 监测批次（批次建立） ============================

    /**
     * 批次建立：以 井组、试验段、回灌班次 组成复合键（另含监测点与业务日期）。
     * 传 shiftId 时直接定位班次；否则按 groupCode/sectionCode/bizDate/shiftIndex 解析，
     * 班次不存在时在同事务内隐式建班。建批同时保存井口快照。
     */
    @Transactional
    public MonitorBatch createBatch(BatchCreateRequest req) {
        final ReinjectionShift shift;
        final MonitorPoint point;
        if (req.getShiftId() != null) {
            shift = requireShift(req.getShiftId());
            assertSameTenant(shift.getTenantId());
            if (req.getMonitorPointId() == null) {
                throw new BizException("VALIDATION", "指定班次时必须提供 monitorPointId");
            }
            point = requirePoint(req.getMonitorPointId());
            assertSameTenant(point.getTenantId());
        } else {
            if (req.getGroupCode() == null || req.getSectionCode() == null || req.getBizDate() == null) {
                throw new BizException("VALIDATION",
                        "复合键建批必须提供 groupCode、sectionCode、bizDate（pointCode 或 monitorPointId、shiftIndex 缺省取 1）");
            }
            TestSection section = requireSectionByCode(req.getSectionCode());
            WellGroup group = requireGroup(section.getWellGroupId());
            if (!group.getGroupCode().equals(req.getGroupCode())) {
                throw new BizException("COMPOSITE_KEY_MISMATCH",
                        "井组与试验段不匹配: " + req.getGroupCode() + " / " + req.getSectionCode());
            }
            int shiftIndex = req.getShiftIndex() == null ? 1 : req.getShiftIndex();
            point = resolvePoint(section.getId(), req.getPointCode(), req.getMonitorPointId());
            shift = findOrCreateShift(section, group, req.getBizDate(), shiftIndex);
        }

        // 复合键 (shift, point, bizDate) 唯一
        LocalDate bizDate = req.getBizDate() != null ? req.getBizDate() : shift.getShiftDate();
        if (batchMapper.selectCount(new QueryWrapper<MonitorBatch>()
                .eq("shift_id", shift.getId())
                .eq("monitor_point_id", point.getId())
                .eq("biz_date", bizDate)) > 0) {
            throw new BizException("DUPLICATE_KEY",
                    "同班次/监测点/业务日期的批次已存在: " + shift.getShiftCode() + "/" + point.getPointCode() + "/" + bizDate);
        }

        TestSection section = sectionMapper.selectById(shift.getTestSectionId());
        WellGroup group = requireGroup(section.getWellGroupId());
        String batchNo = shift.getShiftCode() + "-" + point.getPointCode();
        if (batchMapper.selectCount(new QueryWrapper<MonitorBatch>().eq("batch_no", batchNo)) > 0) {
            throw new BizException("DUPLICATE_KEY", "批次号已存在: " + batchNo);
        }

        MonitorBatch batch = new MonitorBatch();
        batch.setTenantId(section.getTenantId());
        batch.setWellGroupId(group.getId());
        batch.setTestSectionId(section.getId());
        batch.setShiftId(shift.getId());
        batch.setMonitorPointId(point.getId());
        batch.setBatchNo(batchNo);
        batch.setBizDate(bizDate);
        batch.setStatus(BatchStatus.DRAFT.name());
        batch.setWellheadSnapshot(auditService.toJson(buildWellheadSnapshot(group, section, shift, point, bizDate, null)));
        batchMapper.insert(batch);

        auditService.record("MONITOR_BATCH", batch.getId(), "CREATE", AuditService.snapshot(
                "batchNo", batchNo, "shiftCode", shift.getShiftCode(), "pointCode", point.getPointCode(),
                "bizDate", String.valueOf(bizDate), "version", batch.getVersion()));
        return batch;
    }

    private ReinjectionShift findOrCreateShift(TestSection section, WellGroup group, LocalDate date, int index) {
        ReinjectionShift existing = shiftMapper.selectOne(new QueryWrapper<ReinjectionShift>()
                .eq("test_section_id", section.getId())
                .eq("shift_date", date)
                .eq("shift_index", index), false);
        if (existing != null) {
            return existing;
        }
        String shiftCode = buildShiftCode(group.getGroupCode(), section.getSectionCode(), date, index);
        assertShiftUnique(section.getId(), date, index, shiftCode);
        ReinjectionShift shift = new ReinjectionShift();
        shift.setTenantId(section.getTenantId());
        shift.setTestSectionId(section.getId());
        shift.setShiftDate(date);
        shift.setShiftIndex(index);
        shift.setShiftCode(shiftCode);
        shift.setStatus(ShiftStatus.OPEN.name());
        shiftMapper.insert(shift);
        return shift;
    }

    private MonitorPoint resolvePoint(Long sectionId, String pointCode, Long monitorPointId) {
        if (monitorPointId != null) {
            MonitorPoint p = requirePoint(monitorPointId);
            if (!p.getTestSectionId().equals(sectionId)) {
                throw new BizException("COMPOSITE_KEY_MISMATCH", "监测点不属于该试验段: " + monitorPointId);
            }
            return p;
        }
        if (pointCode == null) {
            throw new BizException("VALIDATION", "必须提供 pointCode 或 monitorPointId");
        }
        MonitorPoint p = pointMapper.selectOne(new QueryWrapper<MonitorPoint>().eq("point_code", pointCode), false);
        if (p == null) {
            throw new BizException("NOT_FOUND", "监测点不存在: " + pointCode);
        }
        if (!p.getTestSectionId().equals(sectionId)) {
            throw new BizException("COMPOSITE_KEY_MISMATCH", "监测点不属于该试验段: " + pointCode);
        }
        return p;
    }

    // ============================ 读数原子落库 ============================

    /**
     * 压力、温度、流量与回灌量同批次原子落库：
     * 任一条失败整体回滚；每条读数保存井口快照；批次 DRAFT -> RECORDED。
     */
    @Transactional
    public MonitorBatch submitReadings(Long batchId, ReadingSubmitRequest req) {
        MonitorBatch batch = requireBatch(batchId);
        BatchStatus status = BatchStatus.valueOf(batch.getStatus());
        // 压力/温度/流量/回灌量的原子落库是一次性的 DRAFT -> RECORDED 提交：
        // 已提交（含被并发请求抢先推进）或已验收/落账的批次都不能再次写入，
        // 既保证业务语义，也让并发提交在任意交错时序下都恰好只有一个请求成功。
        if (status != BatchStatus.DRAFT) {
            throw new BizException("INVALID_TRANSITION",
                    "批次不是草稿状态（当前 " + status + "），不能重复提交读数: " + batch.getBatchNo());
        }
        ReinjectionShift shift = requireShift(batch.getShiftId());
        TestSection section = requireSection(shift.getTestSectionId());
        WellGroup group = requireGroup(section.getWellGroupId());
        MonitorPoint point = requirePoint(batch.getMonitorPointId());
        String tz = timezone();

        List<Map<String, Object>> readingSnapshots = new ArrayList<>();
        for (ReadingSubmitRequest.ReadingItem item : req.getReadings()) {
            LocalDateTime readingTime = item.getReadingTimeLocal() == null
                    || item.getReadingTimeLocal().trim().isEmpty()
                    ? LocalDateTime.now() : LocalDateTime.parse(item.getReadingTimeLocal());

            WellheadReading reading = new WellheadReading();
            reading.setBatchId(batchId);
            reading.setReadingTimeLocal(readingTime);
            reading.setPressureMpa(item.getPressureMpa());
            reading.setTemperatureC(item.getTemperatureC());
            reading.setFlowM3h(item.getFlowM3h());
            reading.setInjectionVolumeM3(item.getInjectionVolumeM3());
            Map<String, Object> snapshot = buildWellheadSnapshot(group, section, shift, point,
                    batch.getBizDate(), item);
            snapshot.put("readingTimeLocal", String.valueOf(readingTime));
            snapshot.put("timezone", tz);
            reading.setWellheadSnapshot(auditService.toJson(snapshot));
            readingMapper.insert(reading);

            readingSnapshots.add(AuditService.snapshot(
                    "readingId", reading.getId(),
                    "readingTimeLocal", String.valueOf(readingTime),
                    "pressureMpa", item.getPressureMpa(),
                    "temperatureC", item.getTemperatureC(),
                    "flowM3h", item.getFlowM3h(),
                    "injectionVolumeM3", item.getInjectionVolumeM3()));
        }

        // 状态流转 + 乐观锁：并发提交只有一个线程能推进版本
        Integer fromVersion = batch.getVersion();
        if (status == BatchStatus.DRAFT) {
            batch.setStatus(BatchStatus.RECORDED.name());
            int rows = batchMapper.updateById(batch);
            if (rows == 0) {
                throw new BizException("CONCURRENT_UPDATE", "批次已被其他请求更新，请刷新后重试: " + batch.getBatchNo());
            }
        }

        // 同步刷新建批井口快照（同事务）
        MonitorBatch fresh = batchMapper.selectById(batchId);
        auditService.record("MONITOR_BATCH", batchId, "SUBMIT_READINGS", AuditService.snapshot(
                "batchNo", batch.getBatchNo(),
                "fromVersion", fromVersion,
                "toVersion", fresh.getVersion(),
                "status", fresh.getStatus(),
                "readingCount", readingSnapshots.size(),
                "readings", readingSnapshots));
        return fresh;
    }

    // ============================ 批次状态流转 ============================

    /** RECORDED -> ACCEPTED，冻结版本快照 */
    @Transactional
    public MonitorBatch acceptBatch(Long batchId) {
        MonitorBatch batch = requireBatch(batchId);
        BatchStatus status = BatchStatus.valueOf(batch.getStatus());
        if (status == BatchStatus.ACCEPTED || status == BatchStatus.ACCOUNTED) {
            throw new BizException("INVALID_TRANSITION", "批次已验收/落账，不能重复验收: " + batch.getBatchNo());
        }
        if (status != BatchStatus.RECORDED) {
            throw new BizException("INVALID_TRANSITION", "批次尚未记录监测数据，不能验收: " + batch.getBatchNo());
        }
        Long readingCount = readingMapper.selectCount(new QueryWrapper<WellheadReading>().eq("batch_id", batchId));
        if (readingCount == 0) {
            throw new BizException("INVALID_TRANSITION", "批次没有任何井口读数，不能验收: " + batch.getBatchNo());
        }
        Integer frozenVersion = batch.getVersion();
        batch.setStatus(BatchStatus.ACCEPTED.name());
        batch.setAcceptedVersion(frozenVersion);
        batch.setAcceptedAt(LocalDateTime.now());
        batch.setAcceptedBy(RequestContext.currentOperatorId());
        int rows = batchMapper.updateById(batch);
        if (rows == 0) {
            throw new BizException("CONCURRENT_UPDATE", "批次已被其他请求更新，请刷新后重试: " + batch.getBatchNo());
        }
        auditService.record("MONITOR_BATCH", batchId, "ACCEPT", AuditService.snapshot(
                "batchNo", batch.getBatchNo(), "acceptedVersion", frozenVersion,
                "readingCount", readingCount, "timezone", timezone()));
        return batchMapper.selectById(batchId);
    }

    /** ACCEPTED -> ACCOUNTED（落账） */
    @Transactional
    public MonitorBatch accountBatch(Long batchId, Long accountingId) {
        if (accountingId == null) {
            throw new BizException("VALIDATION", "落账必须提供 accountingId");
        }
        MonitorBatch batch = requireBatch(batchId);
        BatchStatus status = BatchStatus.valueOf(batch.getStatus());
        if (status == BatchStatus.ACCOUNTED) {
            throw new BizException("INVALID_TRANSITION", "批次已落账，不能重复落账: " + batch.getBatchNo());
        }
        if (status != BatchStatus.ACCEPTED) {
            throw new BizException("INVALID_TRANSITION", "只有已验收批次才能落账: " + batch.getBatchNo());
        }
        batch.setStatus(BatchStatus.ACCOUNTED.name());
        batch.setAccountingId(accountingId);
        int rows = batchMapper.updateById(batch);
        if (rows == 0) {
            throw new BizException("CONCURRENT_UPDATE", "批次已被其他请求更新，请刷新后重试: " + batch.getBatchNo());
        }
        auditService.record("MONITOR_BATCH", batchId, "ACCOUNT", AuditService.snapshot(
                "batchNo", batch.getBatchNo(), "accountingId", accountingId,
                "acceptedVersion", batch.getAcceptedVersion(), "timezone", timezone()));
        return batchMapper.selectById(batchId);
    }

    /** 删除保护：已验收或已落账记录不能直接删除 */
    @Transactional
    public void deleteBatch(Long batchId) {
        MonitorBatch batch = requireBatch(batchId);
        BatchStatus status = BatchStatus.valueOf(batch.getStatus());
        if (status.isDeleteProtected()) {
            throw new BizException("DELETE_REJECTED",
                    "已" + (status == BatchStatus.ACCEPTED ? "验收" : "落账") + "记录不能直接删除: " + batch.getBatchNo());
        }
        readingMapper.delete(new QueryWrapper<WellheadReading>().eq("batch_id", batchId));
        batchMapper.deleteById(batchId);
        auditService.record("MONITOR_BATCH", batchId, "DELETE", AuditService.snapshot(
                "batchNo", batch.getBatchNo(), "status", status.name(), "version", batch.getVersion()));
    }

    // ============================ 关联查询 ============================

    public BatchDetailView getBatchDetail(Long batchId) {
        MonitorBatch batch = requireBatch(batchId);
        return assembleDetail(batch);
    }

    /** 按 井组-试验段-班次（日期+序号）-监测点-业务日期 复合键关联查询 */
    public BatchDetailView getBatchDetailByCompositeKey(String groupCode, String sectionCode,
                                                        LocalDate shiftDate, Integer shiftIndex,
                                                        String pointCode, LocalDate bizDate) {
        WellGroup group = requireGroupByCode(groupCode);
        TestSection section = requireSectionByCode(sectionCode);
        if (!section.getWellGroupId().equals(group.getId())) {
            throw new BizException("COMPOSITE_KEY_MISMATCH", "井组与试验段不匹配");
        }
        int idx = shiftIndex == null ? 1 : shiftIndex;
        ReinjectionShift shift = shiftMapper.selectOne(new QueryWrapper<ReinjectionShift>()
                .eq("test_section_id", section.getId())
                .eq("shift_date", shiftDate)
                .eq("shift_index", idx), false);
        if (shift == null) {
            throw new BizException("NOT_FOUND",
                    "班次不存在: " + buildShiftCode(groupCode, sectionCode, shiftDate, idx));
        }
        MonitorPoint point = requirePointByCode(pointCode);
        MonitorBatch batch = batchMapper.selectOne(new QueryWrapper<MonitorBatch>()
                .eq("shift_id", shift.getId())
                .eq("monitor_point_id", point.getId())
                .eq("biz_date", bizDate), false);
        if (batch == null) {
            throw new BizException("NOT_FOUND", "复合键对应的监测批次不存在");
        }
        return assembleDetail(batch);
    }

    private BatchDetailView assembleDetail(MonitorBatch batch) {
        ReinjectionShift shift = requireShift(batch.getShiftId());
        TestSection section = requireSection(shift.getTestSectionId());
        WellGroup group = requireGroup(section.getWellGroupId());
        MonitorPoint point = requirePoint(batch.getMonitorPointId());

        BatchDetailView view = new BatchDetailView();
        view.setBatchId(batch.getId());
        view.setBatchNo(batch.getBatchNo());
        view.setStatus(batch.getStatus());
        view.setBizDate(batch.getBizDate());
        view.setAcceptedVersion(batch.getAcceptedVersion());
        view.setAccountingId(batch.getAccountingId());
        view.setAcceptedAt(batch.getAcceptedAt());
        view.setWellheadSnapshot(batch.getWellheadSnapshot());
        view.setBatchVersion(batch.getVersion());

        view.setGroupId(group.getId());
        view.setGroupCode(group.getGroupCode());
        view.setGroupName(group.getGroupName());
        view.setSectionId(section.getId());
        view.setSectionCode(section.getSectionCode());
        view.setSectionName(section.getSectionName());
        view.setShiftId(shift.getId());
        view.setShiftCode(shift.getShiftCode());
        view.setShiftIndex(shift.getShiftIndex());
        view.setShiftStatus(shift.getStatus());
        view.setPointId(point.getId());
        view.setPointCode(point.getPointCode());
        view.setPointName(point.getPointName());
        view.setWellName(point.getWellName());

        List<WellheadReading> readings = readingMapper.selectList(
                new QueryWrapper<WellheadReading>().eq("batch_id", batch.getId())
                        .orderByAsc("reading_time_local", "id"));
        List<BatchDetailView.ReadingView> views = new ArrayList<>();
        for (WellheadReading r : readings) {
            BatchDetailView.ReadingView rv = new BatchDetailView.ReadingView();
            rv.setId(r.getId());
            rv.setReadingTimeLocal(r.getReadingTimeLocal());
            rv.setPressureMpa(r.getPressureMpa());
            rv.setTemperatureC(r.getTemperatureC());
            rv.setFlowM3h(r.getFlowM3h());
            rv.setInjectionVolumeM3(r.getInjectionVolumeM3());
            rv.setWellheadSnapshot(r.getWellheadSnapshot());
            views.add(rv);
        }
        view.setReadings(views);
        return view;
    }

    public List<MonitorBatch> listBatches(String status) {
        QueryWrapper<MonitorBatch> qw = new QueryWrapper<>();
        scopeApplier.scopeByGroupColumn(qw, currentScope(), "well_group_id");
        if (status != null && !status.trim().isEmpty()) {
            qw.eq("status", status);
        }
        return batchMapper.selectList(qw.orderByDesc("biz_date").orderByAsc("batch_no"));
    }

    // ============================ 井口快照 ============================

    private Map<String, Object> buildWellheadSnapshot(WellGroup group, TestSection section,
                                                      ReinjectionShift shift, MonitorPoint point,
                                                      LocalDate bizDate, ReadingSubmitRequest.ReadingItem item) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("timezone", timezone());
        snap.put("bizDate", String.valueOf(bizDate));
        snap.put("wellGroup", AuditService.snapshot("id", group.getId(), "code", group.getGroupCode(),
                "name", group.getGroupName(), "version", group.getVersion()));
        snap.put("testSection", AuditService.snapshot("id", section.getId(), "code", section.getSectionCode(),
                "name", section.getSectionName(), "version", section.getVersion()));
        snap.put("shift", AuditService.snapshot("id", shift.getId(), "code", shift.getShiftCode(),
                "date", String.valueOf(shift.getShiftDate()), "index", shift.getShiftIndex(),
                "status", shift.getStatus(), "version", shift.getVersion()));
        snap.put("monitorPoint", AuditService.snapshot("id", point.getId(), "code", point.getPointCode(),
                "wellName", point.getWellName(), "version", point.getVersion()));
        if (item != null) {
            snap.put("wellhead", AuditService.snapshot(
                    "pressureMpa", item.getPressureMpa(),
                    "temperatureC", item.getTemperatureC(),
                    "flowM3h", item.getFlowM3h(),
                    "injectionVolumeM3", item.getInjectionVolumeM3()));
        }
        return snap;
    }

    // ============================ require helpers ============================

    private WellGroup requireGroup(Long id) {
        WellGroup g = groupMapper.selectById(id);
        if (g == null) {
            throw new BizException("NOT_FOUND", "井组不存在: " + id);
        }
        assertSameTenant(g.getTenantId());
        return g;
    }

    private WellGroup requireGroupByCode(String code) {
        if (code == null) {
            throw new BizException("VALIDATION", "缺少 groupCode");
        }
        WellGroup g = groupMapper.selectOne(new QueryWrapper<WellGroup>()
                .eq("group_code", code).eq("tenant_id", currentTenantId()), false);
        if (g == null) {
            throw new BizException("NOT_FOUND", "井组不存在: " + code);
        }
        return g;
    }

    private TestSection requireSection(Long id) {
        TestSection s = sectionMapper.selectById(id);
        if (s == null) {
            throw new BizException("NOT_FOUND", "试验段不存在: " + id);
        }
        assertSameTenant(s.getTenantId());
        return s;
    }

    private TestSection requireSectionByCode(String code) {
        if (code == null) {
            throw new BizException("VALIDATION", "缺少 sectionCode");
        }
        TestSection s = sectionMapper.selectOne(new QueryWrapper<TestSection>()
                .eq("section_code", code).eq("tenant_id", currentTenantId()), false);
        if (s == null) {
            throw new BizException("NOT_FOUND", "试验段不存在: " + code);
        }
        return s;
    }

    private MonitorPoint requirePoint(Long id) {
        MonitorPoint p = pointMapper.selectById(id);
        if (p == null) {
            throw new BizException("NOT_FOUND", "监测点不存在: " + id);
        }
        assertSameTenant(p.getTenantId());
        return p;
    }

    private MonitorPoint requirePointByCode(String code) {
        if (code == null) {
            throw new BizException("VALIDATION", "缺少 pointCode");
        }
        MonitorPoint p = pointMapper.selectOne(new QueryWrapper<MonitorPoint>()
                .eq("point_code", code).eq("tenant_id", currentTenantId()), false);
        if (p == null) {
            throw new BizException("NOT_FOUND", "监测点不存在: " + code);
        }
        return p;
    }

    private ReinjectionShift requireShift(Long id) {
        ReinjectionShift s = shiftMapper.selectById(id);
        if (s == null) {
            throw new BizException("NOT_FOUND", "回灌班次不存在: " + id);
        }
        assertSameTenant(s.getTenantId());
        return s;
    }

    private MonitorBatch requireBatch(Long id) {
        MonitorBatch b = batchMapper.selectById(id);
        if (b == null) {
            throw new BizException("NOT_FOUND", "监测批次不存在: " + id);
        }
        assertSameTenant(b.getTenantId());
        return b;
    }
}
