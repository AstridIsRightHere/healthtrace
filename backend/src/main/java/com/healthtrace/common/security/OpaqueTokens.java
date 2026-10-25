package com.healthtrace.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 一次性随机令牌（验证/重置链接、邀请码）的生成与摘要。
 * 原令牌只出现在发给用户的链接中，数据库只保存 SHA-256 摘要（ADR-007）。
 * 令牌为 256 位随机数，摘要无需加盐或慢哈希即可抵抗枚举。
 */
public final class OpaqueTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private OpaqueTokens() {
    }

    /** 生成 URL 安全、无填充的 Base64 随机令牌（43 个字符）。 */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 小写十六进制摘要（64 个字符，符合 token_digest VARCHAR(128)）。 */
    public static String digest(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
