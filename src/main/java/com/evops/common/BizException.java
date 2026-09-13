package com.evops.common;

/** 统一业务异常，由 GlobalExceptionHandler 转换为 ApiResponse.fail */
public class BizException extends RuntimeException {
    private final String code;

    public BizException(String message) {
        this("BIZ_ERROR", message);
    }

    public BizException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() { return code; }
}
