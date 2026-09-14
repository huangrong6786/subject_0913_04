package com.evops.geothermal.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BizException;
import com.evops.geothermal.entity.DeviceQuarantine;
import com.evops.geothermal.mapper.DeviceQuarantineMapper;
import com.evops.geothermal.security.CurrentAccount;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 坏传感器隔离名单：被隔离设备（来源设备编码）上送的观测行在导入时逐行拦截。
 * 隔离/解除均落审计；(tenant_id, device_code) 唯一，重复隔离幂等。
 */
@Service
public class DeviceQuarantineService {

    private final DeviceQuarantineMapper quarantineMapper;
    private final AuditService auditService;
    private final CurrentAccount currentAccount;

    public DeviceQuarantineService(DeviceQuarantineMapper quarantineMapper,
                                   AuditService auditService,
                                   CurrentAccount currentAccount) {
        this.quarantineMapper = quarantineMapper;
        this.auditService = auditService;
        this.currentAccount = currentAccount;
    }

    /** 隔离设备（幂等：已隔离则更新原因）。 */
    @Transactional
    public DeviceQuarantine quarantine(String deviceCode, String reason) {
        long tenantId = currentAccount.get().getTenantId();
        DeviceQuarantine existing = find(tenantId, deviceCode);
        if (existing != null) {
            existing.setStatus("QUARANTINED");
            existing.setReason(reason);
            quarantineMapper.updateById(existing);
            auditService.record("DEVICE_QUARANTINE", existing.getId(), "QUARANTINE",
                    AuditService.snapshot("deviceCode", deviceCode, "reason", reason));
            return existing;
        }
        DeviceQuarantine q = new DeviceQuarantine();
        q.setTenantId(tenantId);
        q.setDeviceCode(deviceCode);
        q.setReason(reason);
        q.setStatus("QUARANTINED");
        quarantineMapper.insert(q);
        auditService.record("DEVICE_QUARANTINE", q.getId(), "QUARANTINE",
                AuditService.snapshot("deviceCode", deviceCode, "reason", reason));
        return q;
    }

    /** 解除隔离（幂等：未隔离/已解除直接返回当前状态）。 */
    @Transactional
    public DeviceQuarantine release(String deviceCode) {
        long tenantId = currentAccount.get().getTenantId();
        DeviceQuarantine existing = find(tenantId, deviceCode);
        if (existing == null) {
            throw new BizException("NOT_FOUND", "设备不在隔离名单: " + deviceCode);
        }
        existing.setStatus("RELEASED");
        quarantineMapper.updateById(existing);
        auditService.record("DEVICE_QUARANTINE", existing.getId(), "RELEASE",
                AuditService.snapshot("deviceCode", deviceCode));
        return existing;
    }

    public List<DeviceQuarantine> listActive() {
        return quarantineMapper.selectList(new QueryWrapper<DeviceQuarantine>()
                .eq("tenant_id", currentAccount.get().getTenantId())
                .eq("status", "QUARANTINED")
                .orderByAsc("device_code"));
    }

    private DeviceQuarantine find(long tenantId, String deviceCode) {
        return quarantineMapper.selectOne(new QueryWrapper<DeviceQuarantine>()
                .eq("tenant_id", tenantId).eq("device_code", deviceCode), false);
    }
}
