package com.evops.geothermal.dto;

/**
 * 导入任务汇总视图：文件级计数 + 分片进度 + 幂等命中标记。
 */
public class ImportSummaryView {
    private Long fileId;
    private String fileName;
    private String checksum;
    private String status;
    private Integer shardSize;
    private Integer totalRows;
    private Integer successRows;
    private Integer updatedRows;
    private Integer failedRows;
    private Integer totalShards;
    private Integer doneShards;
    private Integer failedShards;
    /** true = 命中文件校验和幂等（同一文件重复上传，未重复建任务） */
    private boolean idempotent;
    /** true = 本次调用断点续传/重试了历史遗留分片 */
    private boolean resumed;
    private String message;

    public Long getFileId() { return fileId; }
    public void setFileId(Long fileId) { this.fileId = fileId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
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
    public boolean isIdempotent() { return idempotent; }
    public void setIdempotent(boolean idempotent) { this.idempotent = idempotent; }
    public boolean isResumed() { return resumed; }
    public void setResumed(boolean resumed) { this.resumed = resumed; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
