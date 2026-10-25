package com.healthtrace.common.security;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 邮箱的“加密存储 + 受保护规范化索引”（docs/19 ACCOUNT.email）。
 * <ul>
 *   <li>加密：AES-256-GCM，载荷 = 12 字节随机 IV + 密文 + 16 字节认证标签。同一邮箱每次密文不同，不能用于检索。</li>
 *   <li>索引：规范化邮箱的 HMAC-SHA256（小写十六进制），确定性，用于检索和唯一约束；没有密钥无法离线枚举。</li>
 * </ul>
 */
@Component
public class EmailProtector {

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final String HMAC = "HmacSHA256";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec indexKey;
    private final SecureRandom random = new SecureRandom();

    public EmailProtector(SecretKeyProperties properties) {
        this.encryptionKey = new SecretKeySpec(properties.emailEncryptionKeyBytes(), "AES");
        this.indexKey = new SecretKeySpec(properties.emailIndexKeyBytes(), HMAC);
    }

    /** 规范化：去首尾空白并转小写，保证同一邮箱不同写法映射到同一索引。 */
    public static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public String index(String email) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(indexKey);
            byte[] digest = mac.doFinal(normalize(email).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("邮箱索引计算失败", ex);
        }
    }

    public byte[] encrypt(String email) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(normalize(email).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("邮箱加密失败", ex);
        }
    }

    public String decrypt(byte[] payload) {
        if (payload == null || payload.length <= IV_BYTES) {
            throw new IllegalStateException("邮箱密文格式无效");
        }
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            // 不把底层异常消息带出，避免泄露载荷细节
            throw new IllegalStateException("邮箱解密失败");
        }
    }
}
