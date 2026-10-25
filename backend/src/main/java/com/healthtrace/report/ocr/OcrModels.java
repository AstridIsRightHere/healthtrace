package com.healthtrace.report.ocr;

import org.springframework.core.io.InputStreamSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** OCR 供应商无关的输入/输出模型。 */
public final class OcrModels {

    private OcrModels() {
    }

    /**
     * 一页待识别文件。
     *
     * @param content 原件内容；调用方负责可重复打开
     */
    public record OcrInput(long photoId, int pageOrder, String mediaType, String objectKey,
                           InputStreamSource content) {
    }

    /**
     * 识别结果。
     *
     * @param rawText          可读原文（逐行拼接），加密保存（FR-014）
     * @param rawExtraction    供应商原始结构化结果（JSON 字符串），加密保存
     * @param examinationDate  报告单上的检查/采样日期；识别不到为 null
     */
    public record OcrResult(String providerRequestId, String rawText, String rawExtraction,
                            LocalDate examinationDate, List<OcrItem> items) {
    }

    /**
     * 一行检验项目，保持报告单原样文本，不在此处换算或归一。
     *
     * @param confidence 供应商真实返回的置信度（0～1）；未返回时为 null，绝不伪造（FR-007）
     */
    public record OcrItem(String name, String valueText, String unitText, String referenceText,
                          Long sourcePhotoId, BigDecimal confidence) {
    }
}
