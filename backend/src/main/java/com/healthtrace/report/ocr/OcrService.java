package com.healthtrace.report.ocr;

import com.healthtrace.report.ocr.OcrModels.OcrInput;
import com.healthtrace.report.ocr.OcrModels.OcrResult;

import java.util.List;

/**
 * OCR 供应商抽象（ADR-004）。实现只负责“文件 → 结构化草稿”，不写库、不判断权限与同意；
 * 调用方必须在取得本次第三方 OCR 同意后才能调用（NFR-002）。
 */
public interface OcrService {

    /** 供应商标识，写入 ocr_job.provider。 */
    String provider();

    /**
     * 识别一份报告的全部页面。多页结果按页序合并。
     *
     * @throws OcrException 失败时给出稳定错误码与是否可重试
     */
    OcrResult recognize(List<OcrInput> pages) throws OcrException;
}
