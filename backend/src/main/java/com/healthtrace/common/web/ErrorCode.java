package com.healthtrace.common.web;

import org.springframework.http.HttpStatus;

/**
 * 稳定业务错误码（docs/08 API 错误响应规范 · 错误码分段）。码一经发布不重用其含义。
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "HT-1001", "请求字段校验失败", false),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "HT-1002", "请求格式无效", false),
    /** 单文件超过 DEC-008 上限（25 MiB）。 */
    FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "HT-1003", "单个文件不能超过 25 MiB", false),
    /** 服务端按文件头检测的类型不在 JPEG/PNG/HEIC/PDF 之内。 */
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "HT-1004", "仅支持 JPEG、PNG、HEIC、PDF 文件", false),
    /**
     * 请求路径不对应任何接口（客户端调用错误）。与 HT-3001 区分：HT-3001 表示接口存在但资源不存在或无权访问，
     * 已登录用户才会走到这里（未登录先被 Security 拦为 401），不涉及资源存在性。
     */
    ENDPOINT_NOT_FOUND(HttpStatus.NOT_FOUND, "HT-1005", "接口不存在", false),
    /** 接口存在但不支持该 HTTP 方法；响应带 Allow 头。 */
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "HT-1006", "接口不支持该请求方法", false),
    /** 请求体 Content-Type 不被接口接受（区别于 HT-1004 的上传文件类型检测）。 */
    UNSUPPORTED_CONTENT_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "HT-1007", "请求内容类型不受支持", false),

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "HT-2001", "未登录或会话已失效", false),
    BAD_CREDENTIALS(HttpStatus.UNAUTHORIZED, "HT-2002", "账号或密码不匹配", false),
    /** 邮箱未验证的账号不能真实上传（US-001）。 */
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "HT-2003", "请先验证邮箱", false),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "HT-2004", "当前操作不被允许", false),
    /** 状态冲突类的“当前操作禁止”，如邮箱不可用于开通、邮箱已验证。 */
    OPERATION_NOT_ALLOWED(HttpStatus.CONFLICT, "HT-2004", "当前操作不被允许", false),
    CSRF_FAILED(HttpStatus.FORBIDDEN, "HT-2005", "安全校验失败，请刷新页面后重试", false),
    /** 邀请码、验证/重置令牌无效或过期：统一说明，不透露具体原因（docs/08）。 */
    INVALID_CREDENTIAL(HttpStatus.BAD_REQUEST, "HT-2006", "链接或凭据无效或已过期", false),

    /** 档案不存在或当前账号无有效授权：统一 404，不泄露档案是否存在。 */
    PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND, "HT-3001", "档案不存在", false),
    /** 报告/图片不存在或无权访问：与档案同码，统一 404 不泄露存在性。 */
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "HT-3001", "资源不存在", false),

    /** 乐观锁版本不一致：报告已被其他操作修改。 */
    VERSION_CONFLICT(HttpStatus.CONFLICT, "HT-3002", "内容已被修改，请刷新后重试", false),

    REPORT_STATE_CONFLICT(HttpStatus.CONFLICT, "HT-4002", "当前报告状态不允许此操作", false),
    /** 发起第三方 OCR 前未取得本次明确同意（NFR-002）：不调用供应商。 */
    OCR_CONSENT_REQUIRED(HttpStatus.FORBIDDEN, "HT-4003", "需要先同意使用第三方 OCR 识别", false),
    REPORT_FILE_LIMIT(HttpStatus.CONFLICT, "HT-4005", "单份报告最多 20 个文件", false),
    STORAGE_QUOTA_EXCEEDED(HttpStatus.CONFLICT, "HT-4006", "存储空间不足（每个账号上限 1 GiB）", false),

    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "HT-5001", "尝试次数过多，请稍后再试", true),
    OCR_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "HT-5002", "OCR 识别暂不可用，可稍后重试或手动录入", true),
    /** 邮件服务未配置或投递失败：验证/重置邮件无法发出，相关事务整体回滚。 */
    MAIL_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "HT-5003", "邮件服务暂不可用，请稍后重试", true),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "HT-9001", "内部处理失败，请稍后重试", true);

    private final HttpStatus status;
    private final String code;
    private final String defaultMessage;
    private final boolean retryable;

    ErrorCode(HttpStatus status, String code, String defaultMessage, boolean retryable) {
        this.status = status;
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.retryable = retryable;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public boolean retryable() {
        return retryable;
    }
}
