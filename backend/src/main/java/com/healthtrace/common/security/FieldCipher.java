package com.healthtrace.common.security;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 通用字段级加密：AES-256-GCM，载荷 = 12 字节随机 IV + 密文 + 16 字节认证标签（与 EmailProtector 同一格式）。
 * 不同用途使用不同密钥实例，异常信息不回显明文或密钥。
 */
public final class FieldCipher {

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public FieldCipher(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length != 32) {
            throw new IllegalStateException("字段加密密钥必须是 32 字节");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * 解析 Base64 编码的 32 字节密钥；缺失或格式不合格时拒绝启动，错误信息只包含配置项名称。
     */
    public static byte[] decodeKey(String configName, String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException("缺少必需的密钥配置：" + configName);
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("密钥配置不是有效的 Base64：" + configName);
        }
        if (bytes.length != 32) {
            throw new IllegalStateException(configName + " 必须是 32 字节（Base64 编码）");
        }
        return bytes;
    }

    public byte[] encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("字段加密失败");
        }
    }

    public String decrypt(byte[] payload) {
        if (payload == null) {
            return null;
        }
        if (payload.length <= IV_BYTES) {
            throw new IllegalStateException("密文格式无效");
        }
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("字段解密失败");
        }
    }
}
