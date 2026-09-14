package com.evops.geothermal.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

/** 时序规则链建档请求：把规则链绑定到监测点或井组。 */
public class TouChainCreateRequest {
    @NotBlank
    @Size(max = 32)
    private String chainCode;
    @NotBlank
    @Size(max = 64)
    private String chainName;
    /** MONITOR_POINT / WELL_GROUP */
    @NotBlank
    private String targetType;
    @NotNull
    private Long targetId;

    public String getChainCode() { return chainCode; }
    public void setChainCode(String chainCode) { this.chainCode = chainCode; }
    public String getChainName() { return chainName; }
    public void setChainName(String chainName) { this.chainName = chainName; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
}
