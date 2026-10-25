package com.healthtrace.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

/**
 * 把异常映射为统一错误结构。日志只记录错误类别，不记录请求体、邮箱或令牌（docs/22）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        ErrorCode code = ex.getErrorCode();
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code, ex.getMessage(), ex.getFieldErrors()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<FieldErrorItem> items = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldErrorItem(fe.getField(), fe.getCode(), fe.getDefaultMessage()))
                .toList();
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code, code.defaultMessage(), items));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        ErrorCode code = ErrorCode.MALFORMED_REQUEST;
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code));
    }

    /** 路径/查询参数类型不符（如 profileId 非数字）：按请求格式无效处理，而不是 500。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        ErrorCode code = ErrorCode.MALFORMED_REQUEST;
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code));
    }

    /** multipart 超过容器限制（spring.servlet.multipart.*）：与服务端逐文件校验同一错误码。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        ErrorCode code = ErrorCode.FILE_TOO_LARGE;
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code));
    }

    /** 缺少必填的 multipart 文件或参数。 */
    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorResponse> handleMissingPart(Exception ex) {
        String name = ex instanceof MissingServletRequestPartException p ? p.getRequestPartName()
                : ((MissingServletRequestParameterException) ex).getParameterName();
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code, code.defaultMessage(),
                List.of(new FieldErrorItem(name, "NotNull", "缺少必填项"))));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        ErrorResponse body = ErrorResponse.of(ErrorCode.INTERNAL_ERROR);
        // 只记录异常类型与 traceId，异常消息可能带入参数据
        log.error("未预期异常 traceId={} type={}", body.traceId(), ex.getClass().getName());
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status()).body(body);
    }
}
