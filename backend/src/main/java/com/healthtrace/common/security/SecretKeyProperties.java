package com.healthtrace.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.Base64;

/**
 * 应用层密钥（ADR-007、docs/29）。只从环境变量注入，application.yml 中不设默认值：
 * 变量缺失时占位符无法解析，启动直接失败；格式或长度不合格时在此处拒绝启动。
 *
 * @param emailEncryptionKey Base64 编码的 32 字节 AES-256 密钥，用于加密邮箱
 * @param emailIndexKey      Base64 编码的至少 32 字节 HMAC-SHA256 密钥，用于邮箱检索索引
 */
@ConfigurationProperties(prefix = "healthtrace.security")
public record SecretKeyProperties(String emailEncryptionKey, String emailIndexKey) {

    public SecretKeyProperties {
        byte[] enc = decode("healthtrace.security.email-encryption-key", emailEncryptionKey);
        byte[] idx = decode("healthtrace.security.email-index-key", emailIndexKey);
        if (enc.length != 32) {
            throw new IllegalStateException("healthtrace.security.email-encryption-key 必须是 32 字节（Base64 编码）");
        }
        if (idx.length < 32) {
            throw new IllegalStateException("healthtrace.security.email-index-key 至少 32 字节（Base64 编码）");
        }
        // 加密与索引用途分离，不能共用同一把密钥
        if (Arrays.equals(enc, idx)) {
            throw new IllegalStateException("邮箱加密密钥与索引密钥不能相同");
        }
    }

    public byte[] emailEncryptionKeyBytes() {
        return Base64.getDecoder().decode(emailEncryptionKey.trim());
    }

    public byte[] emailIndexKeyBytes() {
        return Base64.getDecoder().decode(emailIndexKey.trim());
    }

    /** 错误信息只包含配置项名称，不回显密钥内容。 */
    private static byte[] decode(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少必需的密钥配置：" + name);
        }
        try {
            return Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("密钥配置不是有效的 Base64：" + name);
        }
    }
}
