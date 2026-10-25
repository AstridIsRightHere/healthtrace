package com.healthtrace.report.ocr;

/**
 * OCR 调用失败。errorCode 为稳定错误码（写入 ocr_job.error_code），message 不含供应商原始响应、凭据或报告内容。
 */
public class OcrException extends Exception {

    /** 网络超时、限流、供应商 5xx 等，可自动重试。 */
    public static final String UNAVAILABLE = "OCR_UNAVAILABLE";
    /** 鉴权失败（凭据错误或过期）：重试无意义，需运维处理。 */
    public static final String AUTH_FAILED = "OCR_AUTH_FAILED";
    /** 图片无法识别、格式不支持、无检验项目等：重试无意义，可手动录入。 */
    public static final String UNRECOGNIZABLE = "OCR_UNRECOGNIZABLE";
    /** 读取原件失败。 */
    public static final String SOURCE_UNAVAILABLE = "OCR_SOURCE_UNAVAILABLE";

    private final String errorCode;
    private final boolean retryable;

    public OcrException(String errorCode, boolean retryable, String message) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public OcrException(String errorCode, boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
