package com.evops.geothermal.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

/**
 * 坏传感器隔离名单：被隔离设备（来源设备编码）上送的观测行
 * 在导入时被逐行拦截（FAILED），不写入观测表，不影响其他设备的行。
 */
@TableName("t_device_quarantine")
public class DeviceQuarantine extends BaseEntity {
    private Long tenantId;
    private String deviceCode;
    private String reason;
    private String status; // QUARANTINED / RELEASED

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
