package com.healthtrace.common.web;

import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 统一错误结构（RFC 9457 application/problem+json，docs/08）。
 * <ul>
 *   <li>标准成员：type（每个 ErrorCode 一个稳定 URI）、title（HTTP 状态短语）、status、detail（面向用户的说明）、instance（请求路径）。</li>
 *   <li>扩展成员：code（HT-xxxx，前端据此分支）、traceId（与响应头 X-Trace-Id 一致）、fieldErrors（非字段错误省略）、retryable。</li>
 * </ul>
 * type 只用于标识问题类别，不保证可解引用。
 */
public final class ProblemDetails {

    public static final String TYPE_BASE = "https://healthtrace.app/problems/";

    private ProblemDetails() {
    }

    public static ProblemDetail of(ErrorCode code, String detail, List<FieldErrorItem> fieldErrors, String instance) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(typeOf(code));
        problem.setTitle(code.status().getReasonPhrase());
        problem.setInstance(instanceOf(instance));
        problem.setProperty("code", code.code());
        problem.setProperty("traceId", currentTraceId());
        if (fieldErrors != null && !fieldErrors.isEmpty()) {
            problem.setProperty("fieldErrors", List.copyOf(fieldErrors));
        }
        problem.setProperty("retryable", code.retryable());
        return problem;
    }

    public static ProblemDetail of(ErrorCode code, String instance) {
        return of(code, code.defaultMessage(), List.of(), instance);
    }

    /** 稳定类型 URI：枚举名转小写短横线，如 VALIDATION_FAILED → .../validation-failed。 */
    public static URI typeOf(ErrorCode code) {
        return URI.create(TYPE_BASE + code.name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    /** 展平为与 MVC 输出一致的 JSON 对象，供不经过消息转换器的 Security 过滤器链使用。 */
    static Map<String, Object> toMap(ProblemDetail problem) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", problem.getType().toString());
        body.put("title", problem.getTitle());
        body.put("status", problem.getStatus());
        body.put("detail", problem.getDetail());
        if (problem.getInstance() != null) {
            body.put("instance", problem.getInstance().toString());
        }
        if (problem.getProperties() != null) {
            body.putAll(problem.getProperties());
        }
        return body;
    }

    /** 优先取 TraceIdFilter 写入 MDC 的本次请求 id，缺失时（如非请求线程）随机生成。 */
    static String currentTraceId() {
        String current = MDC.get(TraceIdFilter.MDC_KEY);
        return current != null && !current.isBlank() ? current : TraceIdFilter.newTraceId();
    }

    private static URI instanceOf(String requestUri) {
        if (requestUri == null || requestUri.isBlank()) {
            return null;
        }
        try {
            return URI.create(requestUri);
        } catch (IllegalArgumentException e) {
            // 容器已编码的路径一般合法；异常时省略 instance，不让错误响应本身失败
            return null;
        }
    }
}
