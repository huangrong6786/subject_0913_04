package com.evops.geothermal.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/** 坏传感器隔离请求。 */
public class DeviceQuarantineRequest {
    @NotBlank(message = "deviceCode 不能为空")
    @Size(max = 64)
    private String deviceCode;
    @Size(max = 255)
    private String reason;

    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
