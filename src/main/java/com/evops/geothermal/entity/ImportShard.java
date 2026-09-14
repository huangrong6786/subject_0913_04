package com.evops.geothermal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 导入分片：断点续传与失败重试的最小单元。
 * 每个分片在独立事务（REQUIRES_NEW）内处理，分片间互不回滚。
 */
@TableName("t_import_shard")
public class ImportShard {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long importFileId;
    private Integer shardIndex;
    private Integer rowStart;
    private Integer rowEnd;
    private String status;
    private Integer attemptCount;
    private Integer successCount;
    private Integer updatedCount;
    private Integer failedCount;
    private String errorMessage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getImportFileId() { return importFileId; }
    public void setImportFileId(Long importFileId) { this.importFileId = importFileId; }
    public Integer getShardIndex() { return shardIndex; }
    public void setShardIndex(Integer shardIndex) { this.shardIndex = shardIndex; }
    public Integer getRowStart() { return rowStart; }
    public void setRowStart(Integer rowStart) { this.rowStart = rowStart; }
    public Integer getRowEnd() { return rowEnd; }
    public void setRowEnd(Integer rowEnd) { this.rowEnd = rowEnd; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }
    public Integer getSuccessCount() { return successCount; }
    public void setSuccessCount(Integer successCount) { this.successCount = successCount; }
    public Integer getUpdatedCount() { return updatedCount; }
    public void setUpdatedCount(Integer updatedCount) { this.updatedCount = updatedCount; }
    public Integer getFailedCount() { return failedCount; }
    public void setFailedCount(Integer failedCount) { this.failedCount = failedCount; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
