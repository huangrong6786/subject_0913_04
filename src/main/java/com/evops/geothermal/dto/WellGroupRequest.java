package com.evops.geothermal.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class WellGroupRequest {
    @NotBlank
    @Size(max = 32)
    private String groupCode;
    @NotBlank
    @Size(max = 64)
    private String groupName;
    @Size(max = 255)
    private String location;
    @Size(max = 48)
    private String timezone;              // 井场时区（缺省 Asia/Shanghai）

    public String getGroupCode() { return groupCode; }
    public void setGroupCode(String groupCode) { this.groupCode = groupCode; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
}
