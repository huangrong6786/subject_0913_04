package com.evops.geothermal.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BizException;
import com.evops.common.RequestContext;
import com.evops.geothermal.csv.CsvParser;
import com.evops.geothermal.dto.ImportRowView;
import com.evops.geothermal.dto.ImportSummaryView;
import com.evops.geothermal.dto.PageResult;
import com.evops.geothermal.entity.DeviceQuarantine;
import com.evops.geothermal.entity.ImportFile;
import com.evops.geothermal.entity.ImportRow;
import com.evops.geothermal.entity.ImportShard;
import com.evops.geothermal.entity.MonitorBatch;
import com.evops.geothermal.entity.MonitorPoint;
import com.evops.geothermal.entity.ReinjectionShift;
import com.evops.geothermal.entity.WellheadObservation;
import com.evops.geothermal.enums.BatchStatus;
import com.evops.geothermal.enums.ImportFileStatus;
import com.evops.geothermal.enums.ImportRowResult;
import com.evops.geothermal.enums.ImportShardStatus;
import com.evops.geothermal.enums.ShiftStatus;
import com.evops.geothermal.mapper.DeviceQuarantineMapper;
import com.evops.geothermal.mapper.ImportFileMapper;
import com.evops.geothermal.mapper.ImportRowMapper;
import com.evops.geothermal.mapper.ImportShardMapper;
import com.evops.geothermal.mapper.MonitorBatchMapper;
import com.evops.geothermal.mapper.MonitorPointMapper;
import com.evops.geothermal.mapper.ReinjectionShiftMapper;
import com.evops.geothermal.mapper.WellheadObservationMapper;
import com.evops.geothermal.security.CurrentAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 观测数据 CSV 批量导入服务。
 *
 * <p>困难级约束实现要点：
 * <ul>
 *   <li><b>分片处理</b>：按 shardSize（默认 1000）切分分片，每分片独立 REQUIRES_NEW 事务，
 *       5 万行级文件分片间互不回滚；行级校验失败只落失败明细，绝不影响同分片其他行。</li>
 *   <li><b>双重幂等</b>：文件级 (tenant_id, checksum) 唯一——同一文件重复上传命中既有任务；
 *       业务键 (tenant_id, monitor_point_id, serial_no) / (tenant_id, monitor_point_id, observed_at)
 *       唯一——网关重发同序列号、跨天补报重复到达时幂等更新（UPDATED），不产生重复记录。</li>
 *   <li><b>断点续传 / 失败重试</b>：原始文件内容入库，分片状态机 PENDING/PROCESSING/SUCCESS/FAILED，
 *       未完成分片可在重复上传或 retry 接口中续跑；行明细按 (file,rowNo) 唯一先清后写。</li>
 *   <li><b>坏传感器隔离</b>：t_device_quarantine 检疫名单内设备的行逐行拦截；物理量纲范围
 *       校验同时拦截传感器故障产生的坏数值。</li>
 *   <li><b>锁定/验收保护</b>：观测日落账批次已验收(ACCEPTED)/已落账(ACCOUNTED)或班次已闭班
 *       (CLOSED)时，该观测行 FAILED，禁止覆盖。</li>
 * </ul>
 */
@Service
public class ObservationImportService {

    private static final Logger log = LoggerFactory.getLogger(ObservationImportService.class);

    // ---- 单位量纲范围（单位校验）：超出即判疑似单位错误/坏传感器 ----
    static final BigDecimal PRESSURE_MIN = new BigDecimal("0");
    static final BigDecimal PRESSURE_MAX = new BigDecimal("60");       // MPa
    static final BigDecimal TEMPERATURE_MIN = new BigDecimal("-20");
    static final BigDecimal TEMPERATURE_MAX = new BigDecimal("150");   // ℃
    static final BigDecimal FLOW_MIN = new BigDecimal("0");
    static final BigDecimal FLOW_MAX = new BigDecimal("500");          // m³/h
    static final BigDecimal INJECTION_MIN = new BigDecimal("0");
    static final BigDecimal INJECTION_MAX = new BigDecimal("100000");  // m³

    /** 序列号格式（序列校验）：字母数字开头，4-64 位，可含 _ - : . */
    static final Pattern SERIAL_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_\\-:.]{3,63}$");

    static final LocalDateTime MIN_OBSERVED_AT = LocalDateTime.of(2000, 1, 1, 0, 0);
    /** 跨天补报允许历史数据；未来数据仅容忍 1 天时钟偏差 */
    static final long FUTURE_TOLERANCE_DAYS = 1;

    private static final List<DateTimeFormatter> TIME_FORMATS = Arrays.asList(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

    private static final int ROW_BATCH_INSERT_CHUNK = 500;
    private static final int PREFETCH_IN_CHUNK = 500;

    private final ImportFileMapper fileMapper;
    private final ImportShardMapper shardMapper;
    private final ImportRowMapper rowMapper;
    private final WellheadObservationMapper observationMapper;
    private final MonitorPointMapper pointMapper;
    private final MonitorBatchMapper batchMapper;
    private final ReinjectionShiftMapper shiftMapper;
    private final DeviceQuarantineMapper quarantineMapper;
    private final AuditService auditService;
    private final CurrentAccount currentAccount;
    private final TransactionTemplate requiresNewTx;
    private final int shardSize;
    /** 单实例内同一导入文件的处理锁：并发同文件上传/续传串行化，分片不会被并发处理。 */
    private final java.util.concurrent.ConcurrentHashMap<Long, Object> fileLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    public ObservationImportService(ImportFileMapper fileMapper,
                                    ImportShardMapper shardMapper,
                                    ImportRowMapper rowMapper,
                                    WellheadObservationMapper observationMapper,
                                    MonitorPointMapper pointMapper,
                                    MonitorBatchMapper batchMapper,
                                    ReinjectionShiftMapper shiftMapper,
                                    DeviceQuarantineMapper quarantineMapper,
                                    AuditService auditService,
                                    CurrentAccount currentAccount,
                                    PlatformTransactionManager transactionManager,
                                    @Value("${evops.import.shard-size:1000}") int shardSize) {
        this.fileMapper = fileMapper;
        this.shardMapper = shardMapper;
        this.rowMapper = rowMapper;
        this.observationMapper = observationMapper;
        this.pointMapper = pointMapper;
        this.batchMapper = batchMapper;
        this.shiftMapper = shiftMapper;
        this.quarantineMapper = quarantineMapper;
        this.auditService = auditService;
        this.currentAccount = currentAccount;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehaviorName("PROPAGATION_REQUIRES_NEW");
        this.shardSize = shardSize <= 0 ? 1000 : shardSize;
    }

    private long currentTenantId() {
        return currentAccount.get().getTenantId();
    }

    // ============================ 上传入口 ============================

    /**
     * CSV 批量导入：文件校验和幂等 + 分片处理 + 断点续传。
     * 同一文件（同租户同 SHA-256）重复上传不重复建任务；若存在未完成分片则续跑（断点续传）。
     */
    public ImportSummaryView importObservations(String fileName, InputStream in) {
        byte[] content = readBytes(fileName, in);
        String checksum = sha256Hex(content);
        long tenantId = currentTenantId();

        ImportFile existing = findByChecksum(tenantId, checksum);
        if (existing != null) {
            // 文件级幂等命中：未完成的继续跑（断点续传），已完成直接返回既有汇总
            boolean resumed = resumeIfNeeded(existing.getId());
            return buildSummary(fileMapper.selectById(existing.getId()), true, resumed);
        }

        ImportFile file;
        try {
            file = registerFile(fileName, checksum, content, tenantId);
        } catch (DuplicateKeyException dke) {
            // 并发同文件上传：唯一索引兜底，转为幂等命中（等待胜出事务可见后重查）
            ImportFile raced = null;
            for (int i = 0; i < 3 && raced == null; i++) {
                raced = findByChecksum(tenantId, checksum);
                if (raced == null) {
                    try {
                        Thread.sleep(50L * (i + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (raced == null) {
                throw dke;
            }
            boolean resumed = resumeIfNeeded(raced.getId());
            return buildSummary(fileMapper.selectById(raced.getId()), true, resumed);
        }
        processAndFinalize(file.getId(), "IMPORT_COMPLETE");
        return buildSummary(fileMapper.selectById(file.getId()), false, false);
    }

    /** 失败分片重试：重跑 FAILED/PENDING/PROCESSING 分片；无待处理分片时幂等返回。 */
    public ImportSummaryView retryFailedShards(Long fileId) {
        ImportFile file = requireFile(fileId);
        if (countPendingShards(fileId) == 0) {
            return buildSummary(file, false, false);
        }
        processAndFinalize(fileId, "IMPORT_RETRY_COMPLETE");
        return buildSummary(fileMapper.selectById(fileId), false, true);
    }

    /** 断点续传：存在未完成分片时续跑并重新定稿。返回是否发生了续跑。 */
    private boolean resumeIfNeeded(Long fileId) {
        if (countPendingShards(fileId) == 0) {
            return false;
        }
        processAndFinalize(fileId, "IMPORT_RESUME_COMPLETE");
        return true;
    }

    /** 文件锁内串行处理未完成分片并定稿：并发同文件上传/续传不会并发处理同一分片。 */
    private void processAndFinalize(Long fileId, String auditAction) {
        synchronized (fileLocks.computeIfAbsent(fileId, k -> new Object())) {
            processPendingShards(fileId);
            finalizeFile(fileId, auditAction);
        }
    }

    // ============================ 注册（文件 + 分片计划） ============================

    private ImportFile registerFile(String fileName, String checksum, byte[] content, long tenantId) {
        return requiresNewTx.execute(status -> {
            List<String> lines = readAllLines(content);
            if (lines.isEmpty()) {
                throw new BizException("CSV_EMPTY", "CSV 文件为空");
            }
            // 表头校验（必需列缺失在文件级直接拒绝，支持中英文别名）
            CsvParser.Header.from(CsvParser.parseLine(stripBom(lines.get(0))));

            List<Integer> dataLineNos = new ArrayList<>();
            for (int i = 1; i < lines.size(); i++) {
                if (!lines.get(i).trim().isEmpty()) {
                    dataLineNos.add(i + 1); // 物理行号（表头为第 1 行）
                }
            }

            ImportFile file = new ImportFile();
            file.setTenantId(tenantId);
            file.setFileName(fileName == null || fileName.trim().isEmpty() ? "observations.csv" : fileName);
            file.setChecksum(checksum);
            file.setContent(content);
            file.setShardSize(shardSize);
            file.setTotalRows(dataLineNos.size());
            int totalShards = dataLineNos.isEmpty() ? 0
                    : (dataLineNos.size() + shardSize - 1) / shardSize;
            file.setTotalShards(totalShards);
            file.setSuccessRows(0);
            file.setUpdatedRows(0);
            file.setFailedRows(0);
            file.setDoneShards(0);
            file.setFailedShards(0);
            file.setStatus(ImportFileStatus.PROCESSING.name());
            fileMapper.insert(file);

            for (int s = 0; s < totalShards; s++) {
                int from = s * shardSize;
                int to = Math.min(from + shardSize, dataLineNos.size());
                ImportShard shard = new ImportShard();
                shard.setImportFileId(file.getId());
                shard.setShardIndex(s);
                shard.setRowStart(dataLineNos.get(from));
                shard.setRowEnd(dataLineNos.get(to - 1));
                shard.setStatus(ImportShardStatus.PENDING.name());
                shard.setAttemptCount(0);
                shard.setSuccessCount(0);
                shard.setUpdatedCount(0);
                shard.setFailedCount(0);
                shard.setCreateTime(LocalDateTime.now());
                shard.setUpdateTime(LocalDateTime.now());
                shardMapper.insert(shard);
            }
            return file;
        });
    }

    // ============================ 分片处理 ============================

    private long countPendingShards(Long fileId) {
        return shardMapper.selectCount(new QueryWrapper<ImportShard>()
                .eq("import_file_id", fileId)
                .in("status", ImportShardStatus.PENDING.name(),
                        ImportShardStatus.PROCESSING.name(), ImportShardStatus.FAILED.name()));
    }

    private void processPendingShards(Long fileId) {
        List<ImportShard> pending = shardMapper.selectList(new QueryWrapper<ImportShard>()
                .eq("import_file_id", fileId)
                .in("status", ImportShardStatus.PENDING.name(),
                        ImportShardStatus.PROCESSING.name(), ImportShardStatus.FAILED.name())
                .orderByAsc("shard_index"));
        for (ImportShard shard : pending) {
            // 尝试计数与 PROCESSING 置位放在独立小事务：处理中崩溃后可被断点续传识别
            final Long shardId = shard.getId();
            requiresNewTx.execute(s -> {
                ImportShard fresh = shardMapper.selectById(shardId);
                fresh.setAttemptCount(fresh.getAttemptCount() + 1);
                fresh.setStatus(ImportShardStatus.PROCESSING.name());
                fresh.setUpdateTime(LocalDateTime.now());
                shardMapper.updateById(fresh);
                return null;
            });
            try {
                requiresNewTx.execute(s -> {
                    processShard(shardId);
                    return null;
                });
            } catch (Exception ex) {
                // 分片级失败隔离：仅标记本分片 FAILED（可重试），不影响其他分片
                log.error("导入分片处理失败 shardId={}", shardId, ex);
                requiresNewTx.execute(s -> {
                    ImportShard fresh = shardMapper.selectById(shardId);
                    fresh.setStatus(ImportShardStatus.FAILED.name());
                    fresh.setErrorMessage(truncate(String.valueOf(ex.getMessage()), 512));
                    fresh.setUpdateTime(LocalDateTime.now());
                    shardMapper.updateById(fresh);
                    refreshFileCounters(fileId);
                    return null;
                });
            }
        }
    }

    /**
     * 单个分片的处理（在 REQUIRES_NEW 事务内运行）。
     * 两阶段：先逐行校验（失败行落明细、互不影响），再对合法行预取既有观测并幂等 upsert。
     */
    private void processShard(Long shardId) {
        ImportShard shard = shardMapper.selectById(shardId);
        ImportFile file = fileMapper.selectById(shard.getImportFileId());
        long tenantId = file.getTenantId();

        // 行级幂等：分片重试时先清理旧明细，(import_file_id, row_no) 唯一兜底
        rowMapper.delete(new QueryWrapper<ImportRow>().eq("shard_id", shardId));

        List<String> lines = readAllLines(file.getContent());
        CsvParser.Header header = CsvParser.Header.from(CsvParser.parseLine(stripBom(lines.get(0))));

        ShardContext ctx = new ShardContext(tenantId, file.getId());

        // ---- 阶段一：逐行校验（合法/重复/缺列/坏数值逐行隔离） ----
        List<ValidRow> validRows = new ArrayList<>();
        Map<Integer, ImportRow> resultByRowNo = new TreeMap<>();
        for (int lineNo = Math.max(shard.getRowStart(), 2);
             lineNo <= Math.min(shard.getRowEnd(), lines.size()); lineNo++) {
            String text = lines.get(lineNo - 1);
            if (text.trim().isEmpty()) {
                continue;
            }
            List<String> fields = CsvParser.parseLine(text);
            try {
                validRows.add(validateRow(ctx, header, lineNo, fields));
            } catch (RowReject reject) {
                resultByRowNo.put(lineNo, failedRow(file.getId(), shardId, lineNo, header, fields, reject));
            }
        }

        // ---- 阶段二：预取既有观测（序列号 / 监测点+时刻 两路），再逐行幂等 upsert ----
        prefetchExisting(ctx, validRows);
        int success = 0, updated = 0, failed = 0;
        for (ValidRow valid : validRows) {
            ImportRow row = newResultRow(file.getId(), shardId, valid);
            applyUpsert(ctx, valid, row);
            resultByRowNo.put(valid.rowNo, row);
        }
        for (ImportRow r : resultByRowNo.values()) {
            if (ImportRowResult.SUCCESS.name().equals(r.getResult())) {
                success++;
            } else if (ImportRowResult.UPDATED.name().equals(r.getResult())) {
                updated++;
            } else {
                failed++;
            }
        }

        // 逐行明细批量落库（分块多行 INSERT）
        List<ImportRow> results = new ArrayList<>(resultByRowNo.values());
        for (int i = 0; i < results.size(); i += ROW_BATCH_INSERT_CHUNK) {
            rowMapper.batchInsert(results.subList(i, Math.min(i + ROW_BATCH_INSERT_CHUNK, results.size())));
        }

        ImportShard fresh = shardMapper.selectById(shardId);
        fresh.setStatus(ImportShardStatus.SUCCESS.name());
        fresh.setSuccessCount(success);
        fresh.setUpdatedCount(updated);
        fresh.setFailedCount(failed);
        fresh.setErrorMessage(null);
        fresh.setUpdateTime(LocalDateTime.now());
        shardMapper.updateById(fresh);
        refreshFileCounters(file.getId());
    }

    /** 分片处理上下文：业务缓存（监测点/检疫设备/锁定状态/已存在观测）。 */
    private class ShardContext {
        final long tenantId;
        final Long fileId;
        final Map<String, MonitorPoint> pointCache = new HashMap<>();
        final Set<String> quarantinedDevices = new HashSet<>();
        final Map<String, String> lockReasonCache = new HashMap<>();
        /** (pointId|serialNo) -> 已存在观测（含本分片已插入） */
        final Map<String, WellheadObservation> bySerial = new HashMap<>();
        /** (pointId|observedAt) -> 已存在观测 */
        final Map<String, WellheadObservation> byTime = new HashMap<>();

        ShardContext(long tenantId, Long fileId) {
            this.tenantId = tenantId;
            this.fileId = fileId;
            for (DeviceQuarantine q : quarantineMapper.selectList(new QueryWrapper<DeviceQuarantine>()
                    .eq("tenant_id", tenantId).eq("status", "QUARANTINED"))) {
                quarantinedDevices.add(q.getDeviceCode());
            }
        }
    }

    /** 校验链：缺列 -> 必填 -> 序列号 -> 时间 -> 数值/单位 -> 业务键 -> 坏传感器 -> 锁定/验收。 */
    private ValidRow validateRow(ShardContext ctx, CsvParser.Header header,
                                 int rowNo, List<String> fields) {
        // 1. 缺列（行内列数不足）
        for (CsvParser.Col col : CsvParser.Col.values()) {
            if (header.indexOf(col) >= fields.size()) {
                throw RowReject.of(col.canonical(), null,
                        "缺列：该行仅 " + fields.size() + " 列，不足表头要求的 " + header.columnCount() + " 列");
            }
        }
        // 2. 必填
        String objectCode = requireField(header, fields, CsvParser.Col.OBJECT_CODE);
        String observedAtRaw = requireField(header, fields, CsvParser.Col.OBSERVED_AT);
        String pressureRaw = requireField(header, fields, CsvParser.Col.PRESSURE_MPA);
        String temperatureRaw = requireField(header, fields, CsvParser.Col.TEMPERATURE_C);
        String flowRaw = requireField(header, fields, CsvParser.Col.FLOW_M3H);
        String injectionRaw = requireField(header, fields, CsvParser.Col.INJECTION_VOLUME_M3);
        String sourceDevice = requireField(header, fields, CsvParser.Col.SOURCE_DEVICE);
        String serialNo = requireField(header, fields, CsvParser.Col.SERIAL_NO);

        // 3. 序列校验：序列号格式
        if (!SERIAL_PATTERN.matcher(serialNo).matches()) {
            throw RowReject.of(CsvParser.Col.SERIAL_NO, serialNo,
                    "序列号格式非法（序列校验失败）：需 4-64 位、字母数字开头，可含 _-:.");
        }

        // 4. 时间校验
        LocalDateTime observedAt = parseObservedAt(observedAtRaw);
        if (observedAt.isBefore(MIN_OBSERVED_AT)) {
            throw RowReject.of(CsvParser.Col.OBSERVED_AT, observedAtRaw,
                    "观测时间早于 2000-01-01，超出允许范围");
        }
        if (observedAt.isAfter(LocalDateTime.now().plusDays(FUTURE_TOLERANCE_DAYS))) {
            throw RowReject.of(CsvParser.Col.OBSERVED_AT, observedAtRaw,
                    "观测时间晚于当前时间（未来数据）；跨天补报仅支持历史数据");
        }

        // 5. 数值与单位量纲校验
        BigDecimal pressure = parseMeasure(CsvParser.Col.PRESSURE_MPA, pressureRaw,
                PRESSURE_MIN, PRESSURE_MAX, "MPa");
        BigDecimal temperature = parseMeasure(CsvParser.Col.TEMPERATURE_C, temperatureRaw,
                TEMPERATURE_MIN, TEMPERATURE_MAX, "℃");
        BigDecimal flow = parseMeasure(CsvParser.Col.FLOW_M3H, flowRaw,
                FLOW_MIN, FLOW_MAX, "m³/h");
        BigDecimal injection = parseMeasure(CsvParser.Col.INJECTION_VOLUME_M3, injectionRaw,
                INJECTION_MIN, INJECTION_MAX, "m³");

        // 6. 业务键校验：对象编号必须为本租户已建档监测点
        MonitorPoint point = ctx.pointCache.computeIfAbsent(objectCode, code -> {
            MonitorPoint p = pointMapper.selectOne(new QueryWrapper<MonitorPoint>()
                    .eq("point_code", code).eq("tenant_id", ctx.tenantId), false);
            return p == null ? MonitorPointSentinel.MISSING : p;
        });
        if (point == MonitorPointSentinel.MISSING) {
            throw RowReject.of(CsvParser.Col.OBJECT_CODE, objectCode,
                    "对象编号不存在（业务键校验失败）: " + objectCode);
        }
        if (!"ACTIVE".equals(point.getStatus())) {
            throw RowReject.of(CsvParser.Col.OBJECT_CODE, objectCode,
                    "对象已停用（业务键校验失败）: " + objectCode);
        }

        // 7. 坏传感器隔离：检疫名单内设备的行逐行拦截
        if (ctx.quarantinedDevices.contains(sourceDevice)) {
            throw RowReject.of(CsvParser.Col.SOURCE_DEVICE, sourceDevice,
                    "来源设备已隔离（坏传感器），观测行被拦截: " + sourceDevice);
        }

        // 8. 锁定/验收保护：观测日所属批次已验收/落账或班次已闭班锁定时禁止覆盖
        LocalDate bizDate = observedAt.toLocalDate();
        // HashMap 允许 null 值：containsKey 区分“未查过”与“已查未锁定”，避免每行重复查库
        String lockKey = point.getId() + ":" + bizDate;
        if (!ctx.lockReasonCache.containsKey(lockKey)) {
            ctx.lockReasonCache.put(lockKey, lockReasonOf(point.getId(), bizDate));
        }
        String lockReason = ctx.lockReasonCache.get(lockKey);
        if (lockReason != null) {
            throw RowReject.of(CsvParser.Col.OBSERVED_AT, observedAtRaw, lockReason);
        }

        ValidRow valid = new ValidRow();
        valid.rowNo = rowNo;
        valid.objectCode = objectCode;
        valid.point = point;
        valid.observedAtRaw = observedAtRaw;
        valid.observedAt = observedAt;
        valid.bizDate = bizDate;
        valid.pressure = pressure;
        valid.temperature = temperature;
        valid.flow = flow;
        valid.injection = injection;
        valid.sourceDevice = sourceDevice;
        valid.serialNo = serialNo;
        return valid;
    }

    /** 已锁定/已验收判定：返回 null 表示可写，否则返回拒绝原因。 */
    private String lockReasonOf(Long pointId, LocalDate bizDate) {
        List<MonitorBatch> batches = batchMapper.selectList(new QueryWrapper<MonitorBatch>()
                .eq("monitor_point_id", pointId).eq("biz_date", bizDate));
        for (MonitorBatch b : batches) {
            if (BatchStatus.ACCEPTED.name().equals(b.getStatus())) {
                return "数据已验收（批次 " + b.getBatchNo() + "），禁止覆盖";
            }
            if (BatchStatus.ACCOUNTED.name().equals(b.getStatus())) {
                return "数据已落账（批次 " + b.getBatchNo() + "），禁止覆盖";
            }
        }
        for (MonitorBatch b : batches) {
            ReinjectionShift shift = shiftMapper.selectById(b.getShiftId());
            if (shift != null && ShiftStatus.CLOSED.name().equals(shift.getStatus())) {
                return "班次已闭班锁定（" + shift.getShiftCode() + "），禁止覆盖";
            }
        }
        return null;
    }

    /** 预取既有观测：序列号集合 + (监测点,时刻) 集合两路，避免重复行撞唯一约束。 */
    private void prefetchExisting(ShardContext ctx, List<ValidRow> validRows) {
        Set<String> serials = new HashSet<>();
        Set<Long> pointIds = new HashSet<>();
        Set<LocalDateTime> times = new HashSet<>();
        for (ValidRow v : validRows) {
            serials.add(v.serialNo);
            pointIds.add(v.point.getId());
            times.add(v.observedAt);
        }
        List<String> serialList = new ArrayList<>(serials);
        for (int i = 0; i < serialList.size(); i += PREFETCH_IN_CHUNK) {
            List<String> chunk = serialList.subList(i, Math.min(i + PREFETCH_IN_CHUNK, serialList.size()));
            for (WellheadObservation o : observationMapper.selectList(new QueryWrapper<WellheadObservation>()
                    .eq("tenant_id", ctx.tenantId).in("serial_no", chunk))) {
                ctx.bySerial.put(o.getMonitorPointId() + "|" + o.getSerialNo(), o);
            }
        }
        if (!pointIds.isEmpty() && !times.isEmpty()) {
            List<LocalDateTime> timeList = new ArrayList<>(times);
            for (int i = 0; i < timeList.size(); i += PREFETCH_IN_CHUNK) {
                List<LocalDateTime> chunk = timeList.subList(i, Math.min(i + PREFETCH_IN_CHUNK, timeList.size()));
                for (WellheadObservation o : observationMapper.selectList(new QueryWrapper<WellheadObservation>()
                        .eq("tenant_id", ctx.tenantId)
                        .in("monitor_point_id", pointIds)
                        .in("observed_at", chunk))) {
                    ctx.byTime.put(o.getMonitorPointId() + "|" + o.getObservedAt(), o);
                }
            }
        }
    }

    /** 业务键幂等 upsert：同序列号重发 -> UPDATED；同时刻异序列号 -> FAILED；否则 SUCCESS 新增。 */
    private void applyUpsert(ShardContext ctx, ValidRow valid, ImportRow row) {
        String serialKey = valid.point.getId() + "|" + valid.serialNo;
        String timeKey = valid.point.getId() + "|" + valid.observedAt;

        WellheadObservation existing = ctx.bySerial.get(serialKey);
        if (existing != null) {
            // 网关重发同序列号 / 文件内重复 / 跨文件重复：幂等更新（已锁定行在校验链已被拦截）
            applyValues(existing, valid, ctx, valid.rowNo);
            observationMapper.updateById(existing);
            row.setResult(ImportRowResult.UPDATED.name());
            return;
        }
        WellheadObservation timeConflict = ctx.byTime.get(timeKey);
        if (timeConflict != null) {
            row.setResult(ImportRowResult.FAILED.name());
            row.setFailField(CsvParser.Col.SERIAL_NO.canonical());
            row.setFailValue(truncate(valid.serialNo, 255));
            row.setFailReason(truncate("同一监测点同一观测时刻已存在序列号 " + timeConflict.getSerialNo()
                    + " 的观测（业务键冲突）", 512));
            return;
        }
        WellheadObservation obs = new WellheadObservation();
        obs.setTenantId(ctx.tenantId);
        obs.setMonitorPointId(valid.point.getId());
        applyValues(obs, valid, ctx, valid.rowNo);
        observationMapper.insert(obs);
        ctx.bySerial.put(serialKey, obs);
        ctx.byTime.put(timeKey, obs);
        row.setResult(ImportRowResult.SUCCESS.name());
    }

    private void applyValues(WellheadObservation obs, ValidRow valid, ShardContext ctx, int rowNo) {
        obs.setObjectCode(valid.objectCode);
        obs.setSerialNo(valid.serialNo);
        obs.setSourceDevice(valid.sourceDevice);
        obs.setObservedAt(valid.observedAt);
        obs.setBizDate(valid.bizDate);
        obs.setPressureMpa(valid.pressure);
        obs.setTemperatureC(valid.temperature);
        obs.setFlowM3h(valid.flow);
        obs.setInjectionVolumeM3(valid.injection);
        obs.setImportFileId(ctx.fileId);
        obs.setRowNo(rowNo);
    }

    // ============================ 定稿与查询 ============================

    /** 文件计数器聚合：以分片计数为准（同一事务内可见未提交的分片更新）。 */
    private void refreshFileCounters(Long fileId) {
        List<ImportShard> shards = shardMapper.selectList(
                new QueryWrapper<ImportShard>().eq("import_file_id", fileId));
        int success = 0, updated = 0, failed = 0, done = 0, failedShards = 0;
        for (ImportShard s : shards) {
            success += s.getSuccessCount();
            updated += s.getUpdatedCount();
            failed += s.getFailedCount();
            if (ImportShardStatus.SUCCESS.name().equals(s.getStatus())) {
                done++;
            } else if (ImportShardStatus.FAILED.name().equals(s.getStatus())) {
                failedShards++;
            }
        }
        ImportFile file = fileMapper.selectById(fileId);
        file.setSuccessRows(success);
        file.setUpdatedRows(updated);
        file.setFailedRows(failed);
        file.setDoneShards(done);
        file.setFailedShards(failedShards);
        fileMapper.updateById(file);
    }

    /** 定稿：全部分片处理完毕后计算终态并落审计（同一请求一条审计）。 */
    private void finalizeFile(Long fileId, String auditAction) {
        requiresNewTx.execute(s -> {
            refreshFileCounters(fileId);
            ImportFile file = fileMapper.selectById(fileId);
            String status;
            String message;
            if (file.getFailedShards() > 0) {
                status = ImportFileStatus.FAILED.name();
                message = "存在失败分片（" + file.getFailedShards() + "/" + file.getTotalShards()
                        + "），可通过 retry 接口重试";
            } else if (file.getFailedRows() > 0) {
                status = ImportFileStatus.COMPLETED_WITH_ERRORS.name();
                message = "导入完成，存在失败行 " + file.getFailedRows() + " 条（详见行明细）";
            } else {
                status = ImportFileStatus.COMPLETED.name();
                message = "导入完成";
            }
            file.setStatus(status);
            file.setMessage(message);
            fileMapper.updateById(file);
            auditQuietly("IMPORT_FILE", fileId, auditAction, AuditService.snapshot(
                    "checksum", file.getChecksum(),
                    "totalRows", file.getTotalRows(),
                    "successRows", file.getSuccessRows(),
                    "updatedRows", file.getUpdatedRows(),
                    "failedRows", file.getFailedRows(),
                    "totalShards", file.getTotalShards(),
                    "failedShards", file.getFailedShards(),
                    "status", status));
            return null;
        });
    }

    /** 批量导入为单请求多分片事务，审计在定稿时统一落一条；无请求上下文（后台调用）时跳过。 */
    private void auditQuietly(String objectType, Long objectId, String action, Object snapshot) {
        RequestContext.Ctx ctx = RequestContext.get();
        if (ctx == null || ctx.getRequestNo() == null) {
            return;
        }
        auditService.record(objectType, objectId, action, snapshot);
    }

    public ImportSummaryView getSummary(Long fileId) {
        return buildSummary(requireFile(fileId), false, false);
    }

    /** 行明细分页查询：可按结果过滤（SUCCESS/UPDATED/FAILED），pageSize 1-100。 */
    public PageResult<ImportRowView> getRows(Long fileId, String result, Integer page, Integer pageSize) {
        requireFile(fileId);
        int p = page == null || page < 1 ? 1 : page;
        int size = pageSize == null ? 20 : pageSize;
        if (size < 1 || size > 100) {
            throw new BizException("VALIDATION", "pageSize 仅允许 1-100");
        }
        QueryWrapper<ImportRow> qw = new QueryWrapper<ImportRow>().eq("import_file_id", fileId);
        if (result != null && !result.trim().isEmpty()) {
            String r = result.trim().toUpperCase();
            try {
                ImportRowResult.valueOf(r);
            } catch (IllegalArgumentException ex) {
                throw new BizException("VALIDATION", "非法行结果过滤值: " + result);
            }
            qw.eq("result", r);
        }
        long total = rowMapper.selectCount(qw);
        List<ImportRow> rows = rowMapper.selectList(qw.orderByAsc("row_no")
                .last("LIMIT " + size + " OFFSET " + (p - 1) * size));
        List<ImportRowView> views = new ArrayList<>();
        for (ImportRow r : rows) {
            ImportRowView v = new ImportRowView();
            v.setRowNo(r.getRowNo());
            v.setObjectCode(r.getObjectCode());
            v.setSerialNo(r.getSerialNo());
            v.setObservedAtRaw(r.getObservedAtRaw());
            v.setResult(r.getResult());
            v.setFailField(r.getFailField());
            v.setFailValue(r.getFailValue());
            v.setFailReason(r.getFailReason());
            v.setBizKey(r.getBizKey());
            views.add(v);
        }
        boolean hasMore = (long) p * size < total;
        return new PageResult<>(views, total, size, p, null, hasMore);
    }

    private ImportFile requireFile(Long fileId) {
        ImportFile file = fileMapper.selectById(fileId);
        if (file == null) {
            throw new BizException("NOT_FOUND", "导入任务不存在: " + fileId);
        }
        if (file.getTenantId() != currentTenantId()) {
            throw new BizException("FORBIDDEN", "不能操作其他租户的导入任务");
        }
        return file;
    }

    private ImportSummaryView buildSummary(ImportFile file, boolean idempotent, boolean resumed) {
        ImportSummaryView v = new ImportSummaryView();
        v.setFileId(file.getId());
        v.setFileName(file.getFileName());
        v.setChecksum(file.getChecksum());
        v.setStatus(file.getStatus());
        v.setShardSize(file.getShardSize());
        v.setTotalRows(file.getTotalRows());
        v.setSuccessRows(file.getSuccessRows());
        v.setUpdatedRows(file.getUpdatedRows());
        v.setFailedRows(file.getFailedRows());
        v.setTotalShards(file.getTotalShards());
        v.setDoneShards(file.getDoneShards());
        v.setFailedShards(file.getFailedShards());
        v.setIdempotent(idempotent);
        v.setResumed(resumed);
        v.setMessage(file.getMessage());
        return v;
    }

    // ============================ 行模型与解析工具 ============================

    private static class ValidRow {
        int rowNo;
        String objectCode;
        MonitorPoint point;
        String observedAtRaw;
        LocalDateTime observedAt;
        LocalDate bizDate;
        BigDecimal pressure;
        BigDecimal temperature;
        BigDecimal flow;
        BigDecimal injection;
        String sourceDevice;
        String serialNo;
    }

    /** 行级拒绝：携带失败字段、原值与原因，由 processShard 捕获落明细。 */
    private static class RowReject extends RuntimeException {
        final String field;
        final String value;
        final String reason;

        private RowReject(String field, String value, String reason) {
            super(reason);
            this.field = field;
            this.value = value;
            this.reason = reason;
        }

        static RowReject of(String field, String value, String reason) {
            return new RowReject(field, value, reason);
        }

        static RowReject of(CsvParser.Col col, String value, String reason) {
            return new RowReject(col.canonical(), value, reason);
        }
    }

    /** computeIfAbsent 不支持 null 值，用哨兵表示“对象编号不存在”。 */
    private static final class MonitorPointSentinel {
        static final MonitorPoint MISSING = new MonitorPoint();
    }

    private ImportRow newResultRow(Long fileId, Long shardId, ValidRow valid) {
        ImportRow row = new ImportRow();
        row.setImportFileId(fileId);
        row.setShardId(shardId);
        row.setRowNo(valid.rowNo);
        row.setObjectCode(valid.objectCode);
        row.setSerialNo(valid.serialNo);
        row.setObservedAtRaw(valid.observedAtRaw);
        row.setBizKey(valid.objectCode + "|" + valid.serialNo);
        return row;
    }

    /** 失败行明细：保留原始行号、失败字段、字段原值与失败原因；尽力回填定位信息。 */
    private ImportRow failedRow(Long fileId, Long shardId, int rowNo, CsvParser.Header header,
                                List<String> fields, RowReject reject) {
        ImportRow row = new ImportRow();
        row.setImportFileId(fileId);
        row.setShardId(shardId);
        row.setRowNo(rowNo);
        row.setResult(ImportRowResult.FAILED.name());
        row.setFailField(reject.field);
        row.setFailValue(truncate(reject.value, 255));
        row.setFailReason(truncate(reject.reason, 512));
        row.setObjectCode(truncate(header.get(fields, CsvParser.Col.OBJECT_CODE), 64));
        row.setSerialNo(truncate(header.get(fields, CsvParser.Col.SERIAL_NO), 64));
        row.setObservedAtRaw(truncate(header.get(fields, CsvParser.Col.OBSERVED_AT), 64));
        return row;
    }

    private String requireField(CsvParser.Header header, List<String> fields, CsvParser.Col col) {
        String v = header.get(fields, col);
        if (v == null || v.isEmpty()) {
            throw RowReject.of(col, null, "必填字段为空: " + col.canonical());
        }
        return v;
    }

    private LocalDateTime parseObservedAt(String raw) {
        for (DateTimeFormatter fmt : TIME_FORMATS) {
            try {
                return LocalDateTime.parse(raw, fmt);
            } catch (Exception ignored) {
            }
        }
        throw RowReject.of(CsvParser.Col.OBSERVED_AT, raw,
                "观测时间格式非法，支持 yyyy-MM-dd HH:mm:ss / yyyy-MM-ddTHH:mm:ss");
    }

    private BigDecimal parseMeasure(CsvParser.Col col, String raw,
                                    BigDecimal min, BigDecimal max, String unit) {
        final BigDecimal value;
        try {
            value = new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            throw RowReject.of(col, raw, "数值格式非法（坏数值）: " + raw);
        }
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw RowReject.of(col, raw, "超出物理量纲范围 [" + min.toPlainString() + ", "
                    + max.toPlainString() + "] " + unit + "（单位校验失败，疑似单位错误或坏传感器）");
        }
        return value.setScale(3, RoundingMode.HALF_UP);
    }

    private ImportFile findByChecksum(long tenantId, String checksum) {
        return fileMapper.selectOne(new QueryWrapper<ImportFile>()
                .eq("tenant_id", tenantId).eq("checksum", checksum), false);
    }

    private static byte[] readBytes(String fileName, InputStream in) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            byte[] content = out.toByteArray();
            if (content.length == 0) {
                throw new BizException("CSV_EMPTY", "CSV 文件为空: " + fileName);
            }
            return content;
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException("CSV_READ_ERROR", "CSV 文件读取失败: " + ex.getMessage());
        }
    }

    private static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static List<String> readAllLines(byte[] content) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(content), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (Exception ex) {
            throw new BizException("CSV_READ_ERROR", "CSV 内容解析失败: " + ex.getMessage());
        }
        return lines;
    }

    private static String stripBom(String s) {
        if (s != null && !s.isEmpty() && s.charAt(0) == '﻿') {
            return s.substring(1);
        }
        return s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
