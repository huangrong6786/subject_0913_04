package com.evops.geothermal.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

/**
 * 导入文件：一次 CSV 上传对应一个导入任务。
 * (tenant_id, checksum) 唯一，文件级幂等；content 保存原始内容，
 * 作为断点续传与失败分片重试的数据源。
 */
@TableName("t_import_file")
public class ImportFile extends BaseEntity {
    private Long tenantId;
    private String fileName;
    private String checksum;
    private byte[] content;
    private Integer shardSize;
    private Integer totalRows;
    private Integer successRows;
    private Integer updatedRows;
    private Integer failedRows;
    private Integer totalShards;
    private Integer doneShards;
    private Integer failedShards;
    private String status;
    private String message;

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }
    public byte[] getContent() { return content; }
    public void setContent(byte[] content) { this.content = content; }
    public Integer getShardSize() { return shardSize; }
    public void setShardSize(Integer shardSize) { this.shardSize = shardSize; }
    public Integer getTotalRows() { return totalRows; }
    public void setTotalRows(Integer totalRows) { this.totalRows = totalRows; }
    public Integer getSuccessRows() { return successRows; }
    public void setSuccessRows(Integer successRows) { this.successRows = successRows; }
    public Integer getUpdatedRows() { return updatedRows; }
    public void setUpdatedRows(Integer updatedRows) { this.updatedRows = updatedRows; }
    public Integer getFailedRows() { return failedRows; }
    public void setFailedRows(Integer failedRows) { this.failedRows = failedRows; }
    public Integer getTotalShards() { return totalShards; }
    public void setTotalShards(Integer totalShards) { this.totalShards = totalShards; }
    public Integer getDoneShards() { return doneShards; }
    public void setDoneShards(Integer doneShards) { this.doneShards = doneShards; }
    public Integer getFailedShards() { return failedShards; }
    public void setFailedShards(Integer failedShards) { this.failedShards = failedShards; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
