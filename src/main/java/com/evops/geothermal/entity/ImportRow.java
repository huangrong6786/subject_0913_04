package com.evops.geothermal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 逐行导入明细：每一行的处理结果。
 * 失败行保留原始行号、失败字段、字段原值与失败原因；
 * (import_file_id, row_no) 唯一，分片重试时先清后写，行级幂等。
 */
@TableName("t_import_row")
public class ImportRow {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long importFileId;
    private Long shardId;
    private Integer rowNo;
    private String objectCode;
    private String serialNo;
    private String observedAtRaw;
    private String result;
    private String failField;
    private String failValue;
    private String failReason;
    private String bizKey;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getImportFileId() { return importFileId; }
    public void setImportFileId(Long importFileId) { this.importFileId = importFileId; }
    public Long getShardId() { return shardId; }
    public void setShardId(Long shardId) { this.shardId = shardId; }
    public Integer getRowNo() { return rowNo; }
    public void setRowNo(Integer rowNo) { this.rowNo = rowNo; }
    public String getObjectCode() { return objectCode; }
    public void setObjectCode(String objectCode) { this.objectCode = objectCode; }
    public String getSerialNo() { return serialNo; }
    public void setSerialNo(String serialNo) { this.serialNo = serialNo; }
    public String getObservedAtRaw() { return observedAtRaw; }
    public void setObservedAtRaw(String observedAtRaw) { this.observedAtRaw = observedAtRaw; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getFailField() { return failField; }
    public void setFailField(String failField) { this.failField = failField; }
    public String getFailValue() { return failValue; }
    public void setFailValue(String failValue) { this.failValue = failValue; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public String getBizKey() { return bizKey; }
    public void setBizKey(String bizKey) { this.bizKey = bizKey; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
