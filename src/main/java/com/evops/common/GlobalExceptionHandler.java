package com.evops.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> handleBiz(BizException ex) {
        return ApiResponse.fail(ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().isEmpty() ? "参数校验失败"
                : ex.getBindingResult().getFieldErrors().get(0).getField() + " "
                        + ex.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        return ApiResponse.fail(msg);
    }

    /** GET 查询参数绑定到命令对象失败（如日期格式错误）。 */
    @ExceptionHandler(org.springframework.validation.BindException.class)
    public ApiResponse<Void> handleBind(org.springframework.validation.BindException ex) {
        String msg = ex.getBindingResult().getFieldErrors().isEmpty() ? "查询参数格式错误"
                : ex.getBindingResult().getFieldErrors().get(0).getField() + " 参数格式错误";
        return ApiResponse.fail(msg);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException ex) {
        return ApiResponse.fail(ex.getMessage());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ApiResponse<Void> handleDuplicateKey(DuplicateKeyException ex) {
        return ApiResponse.fail("业务键冲突，记录已存在或请求号重复");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ApiResponse<Void> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("数据完整性冲突: {}", ex.getMostSpecificCause().getMessage());
        return ApiResponse.fail("数据完整性冲突（唯一业务键/外键约束）");
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleUnexpected(Exception ex) {
        log.error("未处理异常", ex);
        return ApiResponse.fail("系统处理失败");
    }
}
