package com.healthtrace.report.ocr;

import com.healthtrace.common.security.FieldCipher;

/** OCR 原文加解密（HT_OCR_ENCRYPTION_KEY，FR-014）。独立类型避免与其他用途的密钥混用。 */
public class OcrTextCipher {

    private final FieldCipher cipher;

    public OcrTextCipher(FieldCipher cipher) {
        this.cipher = cipher;
    }

    public byte[] encrypt(String plaintext) {
        return cipher.encrypt(plaintext);
    }

    public String decrypt(byte[] ciphertext) {
        return cipher.decrypt(ciphertext);
    }
}
