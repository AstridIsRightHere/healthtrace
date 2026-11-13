package com.healthtrace.common.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 文件流式加密：分段边界、篡改/截断/换键检测、密钥长度校验。 */
class FileCipherTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes();
    private final FileCipher cipher = new FileCipher(KEY);
    private static final int SEG = FileCipher.SEGMENT_PLAIN_BYTES;

    private byte[] encrypt(byte[] plain, String objectKey) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (OutputStream out = cipher.encrypt(sink, objectKey)) {
            // 用不规则块大小写入，覆盖跨段拼接
            int off = 0;
            int step = 1;
            while (off < plain.length) {
                int n = Math.min(step, plain.length - off);
                out.write(plain, off, n);
                off += n;
                step = step * 3 % 70_001 + 1;
            }
        }
        return sink.toByteArray();
    }

    private byte[] decrypt(byte[] sealed, String objectKey) throws IOException {
        try (InputStream in = cipher.decrypt(new ByteArrayInputStream(sealed), objectKey)) {
            return in.readAllBytes();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 15, SEG - 1, SEG, SEG + 1, 2 * SEG, 3 * SEG + 777})
    void roundTripsAcrossSegmentBoundaries(int size) throws Exception {
        byte[] plain = new byte[size];
        new Random(size).nextBytes(plain);

        byte[] sealed = encrypt(plain, "reports/1/a/original");

        assertThat(sealed.length).isEqualTo(FileCipher.ciphertextLength(size));
        assertThat(decrypt(sealed, "reports/1/a/original")).isEqualTo(plain);
    }

    @Test
    void sameInputEncryptsDifferentlyEachTime() throws Exception {
        byte[] plain = "same".getBytes();
        assertThat(encrypt(plain, "k")).isNotEqualTo(encrypt(plain, "k"));
    }

    @Test
    void tamperedByteIsDetected() throws Exception {
        byte[] sealed = encrypt(new byte[SEG + 100], "k");
        sealed[FileCipher.HEADER_BYTES + 10] ^= 1;
        assertThatThrownBy(() -> decrypt(sealed, "k")).isInstanceOf(IOException.class);
    }

    @Test
    void truncationAtSegmentBoundaryIsDetected() throws Exception {
        byte[] sealed = encrypt(new byte[2 * SEG + 5], "k");
        // 去掉最后一段：剩下的最后一段不是末段标记，必须失败
        byte[] cut = Arrays.copyOf(sealed, FileCipher.HEADER_BYTES + 2 * (SEG + FileCipher.TAG_BYTES));
        assertThatThrownBy(() -> decrypt(cut, "k")).isInstanceOf(IOException.class);
    }

    @Test
    void wrongObjectKeyOrWrongKeyFails() throws Exception {
        byte[] sealed = encrypt("photo".getBytes(), "reports/1/a/original");
        assertThatThrownBy(() -> decrypt(sealed, "reports/2/a/original")).isInstanceOf(IOException.class);
        FileCipher other = new FileCipher("fedcba9876543210fedcba9876543210".getBytes());
        assertThatThrownBy(() -> {
            try (InputStream in = other.decrypt(new ByteArrayInputStream(sealed), "reports/1/a/original")) {
                in.readAllBytes();
            }
        }).isInstanceOf(IOException.class);
    }

    @Test
    void plaintextFileIsRejected() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        assertThat(FileCipher.hasMagic(jpeg)).isFalse();
        assertThatThrownBy(() -> decrypt(jpeg, "k")).isInstanceOf(IOException.class);
    }

    @Test
    void keyMustBe32Bytes() {
        assertThatThrownBy(() -> new FileCipher(new byte[16])).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new FileCipher(null)).isInstanceOf(IllegalStateException.class);
    }
}
