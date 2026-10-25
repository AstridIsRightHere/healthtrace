package com.healthtrace.report.ocr;

import com.healthtrace.common.security.FieldCipher;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OCR 配置（ADR-004、docs/29）。密钥只从环境变量注入，application.yml 不设默认值：
 * 任一缺失或格式不合格时拒绝启动；错误信息只包含配置项名称，绝不回显密钥。
 *
 * @param encryptionKey Base64 编码的 32 字节 AES-256 密钥，加密 OCR 原文与原始结构化结果（FR-014）
 * @param baidu         百度智能云 OCR 凭据与地址
 */
@ConfigurationProperties(prefix = "healthtrace.ocr")
public record OcrProperties(String encryptionKey, Baidu baidu) {

    public OcrProperties {
        FieldCipher.decodeKey("healthtrace.ocr.encryption-key", encryptionKey);
        if (baidu == null) {
            throw new IllegalStateException("缺少必需的配置：healthtrace.ocr.baidu");
        }
    }

    public byte[] encryptionKeyBytes() {
        return FieldCipher.decodeKey("healthtrace.ocr.encryption-key", encryptionKey);
    }

    /**
     * @param apiKey    HT_BAIDU_OCR_API_KEY
     * @param secretKey HT_BAIDU_OCR_SECRET_KEY
     * @param baseUrl   接口根地址，默认 https://aip.baidubce.com
     */
    public record Baidu(String apiKey, String secretKey, String baseUrl) {

        public Baidu {
            requireText("healthtrace.ocr.baidu.api-key", apiKey);
            requireText("healthtrace.ocr.baidu.secret-key", secretKey);
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "https://aip.baidubce.com";
            }
            baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        }

        /** 防止凭据经日志或异常打印泄露。 */
        @Override
        public String toString() {
            return "Baidu[baseUrl=" + baseUrl + ", apiKey=***, secretKey=***]";
        }
    }

    @Override
    public String toString() {
        return "OcrProperties[encryptionKey=***, baidu=" + baidu + "]";
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少必需的密钥配置：" + name);
        }
    }
}
