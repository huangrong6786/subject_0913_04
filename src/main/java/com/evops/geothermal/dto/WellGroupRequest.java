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

    public String getGroupCode() { return groupCode; }
    public void setGroupCode(String groupCode) { this.groupCode = groupCode; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
}
