package com.evops.geothermal.dto;

/**
 * 逐行导入明细视图：原始行号、结果（SUCCESS/UPDATED/FAILED），
 * 失败行含失败字段、字段原值与失败原因。
 */
public class ImportRowView {
    private Integer rowNo;
    private String objectCode;
    private String serialNo;
    private String observedAtRaw;
    private String result;
    private String failField;
    private String failValue;
    private String failReason;
    private String bizKey;

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
}
