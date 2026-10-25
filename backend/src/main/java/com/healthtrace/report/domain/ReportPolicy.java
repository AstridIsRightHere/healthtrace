package com.healthtrace.report.domain;

import java.time.Duration;

/** 上传容量与格式常量（DEC-008，2026-10-20 已确认）。 */
public final class ReportPolicy {

    /** 单文件上限 25 MiB（原件与预览图分别校验）。 */
    public static final long MAX_FILE_BYTES = 25L * 1024 * 1024;
    /** 单报告最多 20 个文件（图片+PDF 合计）。 */
    public static final int MAX_FILES_PER_REPORT = 20;
    /** 单账号上传总容量初始 1 GiB（原件+预览图合计）。 */
    public static final long MAX_BYTES_PER_ACCOUNT = 1024L * 1024 * 1024;
    /** 报告标题上限（docs/19 REPORT.title VARCHAR(120)），按码点计。 */
    public static final int TITLE_MAX_LENGTH = 120;
    /** 对象存储直链有效期（仅支持签名 URL 的实现使用）。 */
    public static final Duration ACCESS_URL_TTL = Duration.ofMinutes(5);

    private ReportPolicy() {
    }
}
