package com.healthtrace.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 把异常映射为统一错误结构（RFC 9457 problem+json，见 {@link ProblemDetails}）。
 * 日志只记录错误类别，不记录请求体、邮箱或令牌（docs/22）。
 * <p>
 * 不继承 ResponseEntityExceptionHandler：它会为大量框架异常生成自己的 detail 与默认 type，
 * 要保持 HT 错误码与中文说明不变需逐个覆写，反而比显式映射复杂。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApi(ApiException ex, HttpServletRequest request) {
        return respond(ex.getErrorCode(), ex.getMessage(), ex.getFieldErrors(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex,
                                                          HttpServletRequest request) {
        List<FieldErrorItem> items = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldErrorItem(fe.getField(), fe.getCode(), fe.getDefaultMessage()))
                .toList();
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        return respond(code, code.defaultMessage(), items, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException ex,
                                                          HttpServletRequest request) {
        return respond(ErrorCode.MALFORMED_REQUEST, request);
    }

    /**
     * 参数类型不符：
     * <ul>
     *   <li>路径变量（如 profileId 非数字）：URL 本身无效，按请求格式无效 HT-1002 处理。</li>
     *   <li>查询参数（如枚举 status/variant 取值不在范围内）：按字段校验失败 HT-1001，fieldErrors 指向参数名；
     *       枚举类型列出可选值。不回显输入值。</li>
     * </ul>
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest request) {
        if (ex.getParameter().hasParameterAnnotation(PathVariable.class)) {
            return respond(ErrorCode.MALFORMED_REQUEST, request);
        }
        String message = typeMismatchMessage(ex.getName(), ex.getRequiredType());
        return respond(ErrorCode.VALIDATION_FAILED, message,
                List.of(new FieldErrorItem(ex.getName(), "Invalid", message)), request);
    }

    /** multipart 超过容器限制（spring.servlet.multipart.*）：与服务端逐文件校验同一错误码。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleUploadTooLarge(MaxUploadSizeExceededException ex,
                                                              HttpServletRequest request) {
        return respond(ErrorCode.FILE_TOO_LARGE, request);
    }

    /** 缺少必填的 multipart 文件或参数。 */
    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ProblemDetail> handleMissingPart(Exception ex, HttpServletRequest request) {
        String name = ex instanceof MissingServletRequestPartException p ? p.getRequestPartName()
                : ((MissingServletRequestParameterException) ex).getParameterName();
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        return respond(code, code.defaultMessage(), List.of(new FieldErrorItem(name, "NotNull", "缺少必填项")), request);
    }

    /** 路径无对应接口（含静态资源回退）：404 HT-1005，不落入 500。 */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ProblemDetail> handleNotFound(Exception ex, HttpServletRequest request) {
        return respond(ErrorCode.ENDPOINT_NOT_FOUND, request);
    }

    /** 接口存在但方法不支持：405 HT-1006，并按 RFC 9110 返回 Allow 头。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex,
                                                                HttpServletRequest request) {
        ErrorCode code = ErrorCode.METHOD_NOT_ALLOWED;
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(code.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        Set<HttpMethod> allowed = ex.getSupportedHttpMethods();
        if (allowed != null && !allowed.isEmpty()) {
            builder.allow(allowed.toArray(HttpMethod[]::new));
        }
        return builder.body(ProblemDetails.of(code, request.getRequestURI()));
    }

    /** 请求体 Content-Type 不被接受：415 HT-1007。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleUnsupportedContentType(HttpMediaTypeNotSupportedException ex,
                                                                      HttpServletRequest request) {
        return respond(ErrorCode.UNSUPPORTED_CONTENT_TYPE, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        ResponseEntity<ProblemDetail> response = respond(ErrorCode.INTERNAL_ERROR, request);
        // 只记录异常类型与 traceId，异常消息可能带入参数据
        log.error("未预期异常 traceId={} type={}",
                response.getBody().getProperties().get("traceId"), ex.getClass().getName());
        return response;
    }

    private static ResponseEntity<ProblemDetail> respond(ErrorCode code, HttpServletRequest request) {
        return respond(code, code.defaultMessage(), List.of(), request);
    }

    private static ResponseEntity<ProblemDetail> respond(ErrorCode code, String detail,
                                                         List<FieldErrorItem> fieldErrors,
                                                         HttpServletRequest request) {
        return ResponseEntity.status(code.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetails.of(code, detail, fieldErrors, request.getRequestURI()));
    }

    static String typeMismatchMessage(String name, Class<?> requiredType) {
        if (requiredType != null && requiredType.isEnum()) {
            String options = Arrays.stream(requiredType.getEnumConstants())
                    .map(c -> ((Enum<?>) c).name())
                    .collect(Collectors.joining("、"));
            return "参数 " + name + " 取值无效，可选值：" + options;
        }
        return "参数 " + name + " 格式无效";
    }
}
