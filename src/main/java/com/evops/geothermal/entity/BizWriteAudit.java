package com.evops.geothermal.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 跨表写入审计：每次业务写入记录请求号、操作者、业务时区与版本快照 */
@TableName("t_biz_write_audit")
public class BizWriteAudit {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String requestNo;
    private Long operatorId;
    private String operatorName;
    private String bizTimezone;
    private String objectType;
    private Long objectId;
    private String action;
    private String versionSnapshot;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRequestNo() { return requestNo; }
    public void setRequestNo(String requestNo) { this.requestNo = requestNo; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }
    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }
    public String getBizTimezone() { return bizTimezone; }
    public void setBizTimezone(String bizTimezone) { this.bizTimezone = bizTimezone; }
    public String getObjectType() { return objectType; }
    public void setObjectType(String objectType) { this.objectType = objectType; }
    public Long getObjectId() { return objectId; }
    public void setObjectId(Long objectId) { this.objectId = objectId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getVersionSnapshot() { return versionSnapshot; }
    public void setVersionSnapshot(String versionSnapshot) { this.versionSnapshot = versionSnapshot; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
