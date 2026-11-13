package com.healthtrace.common.security;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.EOFException;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 文件级流式加密（照片原图等大对象）：AES-256-GCM 分段认证加密，内存占用与文件大小无关。
 * <p>
 * 格式：头部 {@code "HTE1"}（4 字节魔数）+ 7 字节随机 nonce 前缀；随后是若干段，每段明文最多 64 KiB，
 * 密文 = 明文 + 16 字节认证标签。第 i 段的 IV = nonce 前缀(7) ‖ i(4 字节大端) ‖ 末段标记(1)。
 * 附加认证数据 = 头部 ‖ 对象键（UTF-8），因此：
 * <ul>
 *   <li>任一字节被篡改、段被重排或截断（末段标记缺失）都会解密失败；</li>
 *   <li>把某个对象的密文复制到另一个对象键下也无法解密。</li>
 * </ul>
 * 异常信息不含明文、密钥或对象键。
 */
public final class FileCipher {

    static final byte[] MAGIC = {'H', 'T', 'E', '1'};
    static final int NONCE_PREFIX_BYTES = 7;
    static final int HEADER_BYTES = MAGIC.length + NONCE_PREFIX_BYTES;
    static final int SEGMENT_PLAIN_BYTES = 64 * 1024;
    static final int TAG_BYTES = 16;
    private static final String CIPHER = "AES/GCM/NoPadding";

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public FileCipher(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length != 32) {
            throw new IllegalStateException("文件加密密钥必须是 32 字节");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    /** 文件开头是否为本格式的魔数（用于识别迁移前遗留的明文文件）。 */
    public static boolean hasMagic(byte[] head) {
        return head != null && head.length >= MAGIC.length
                && Arrays.equals(head, 0, MAGIC.length, MAGIC, 0, MAGIC.length);
    }

    /** 明文长度对应的密文总长度：头部 + 明文 + 每段一个标签（空文件也有一个空末段）。 */
    public static long ciphertextLength(long plainBytes) {
        long segments = Math.max(1, (plainBytes + SEGMENT_PLAIN_BYTES - 1) / SEGMENT_PLAIN_BYTES);
        return HEADER_BYTES + plainBytes + segments * TAG_BYTES;
    }

    /** 返回加密输出流：写入明文，关闭时写出末段并关闭底层流。 */
    public OutputStream encrypt(OutputStream sink, String objectKey) throws IOException {
        byte[] prefix = new byte[NONCE_PREFIX_BYTES];
        random.nextBytes(prefix);
        byte[] header = ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).put(prefix).array();
        sink.write(header);
        return new EncryptingStream(sink, header, aad(header, objectKey));
    }

    /** 返回解密输入流：头部无效、认证失败或被截断时读取抛出 IOException。 */
    public InputStream decrypt(InputStream source, String objectKey) throws IOException {
        byte[] header = source.readNBytes(HEADER_BYTES);
        if (header.length != HEADER_BYTES || !hasMagic(header)) {
            throw new IOException("加密文件头无效");
        }
        return new DecryptingStream(source, header, aad(header, objectKey));
    }

    private static byte[] aad(byte[] header, String objectKey) {
        byte[] keyBytes = objectKey.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(header.length + keyBytes.length).put(header).put(keyBytes).array();
    }

    private static byte[] iv(byte[] header, int index, boolean last) {
        return ByteBuffer.allocate(12)
                .put(header, MAGIC.length, NONCE_PREFIX_BYTES)
                .putInt(index)
                .put((byte) (last ? 1 : 0))
                .array();
    }

    private Cipher cipher(int mode, byte[] header, int index, boolean last, byte[] aad) throws IOException {
        try {
            Cipher c = Cipher.getInstance(CIPHER);
            c.init(mode, key, new GCMParameterSpec(TAG_BYTES * 8, iv(header, index, last)));
            c.updateAAD(aad);
            return c;
        } catch (GeneralSecurityException e) {
            throw new IOException("文件加密初始化失败");
        }
    }

    private final class EncryptingStream extends FilterOutputStream {
        private final byte[] header;
        private final byte[] aad;
        private final byte[] buffer = new byte[SEGMENT_PLAIN_BYTES];
        private int filled;
        private int index;
        private boolean closed;

        EncryptingStream(OutputStream out, byte[] header, byte[] aad) {
            super(out);
            this.header = header;
            this.aad = aad;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                if (filled == buffer.length) {
                    // 缓冲满且还有后续数据：当前段一定不是末段
                    flushSegment(false);
                }
                int n = Math.min(len, buffer.length - filled);
                System.arraycopy(b, off, buffer, filled, n);
                filled += n;
                off += n;
                len -= n;
            }
        }

        private void flushSegment(boolean last) throws IOException {
            if (index == Integer.MAX_VALUE) {
                throw new IOException("文件过大");
            }
            Cipher c = cipher(Cipher.ENCRYPT_MODE, header, index++, last, aad);
            try {
                out.write(c.doFinal(buffer, 0, filled));
            } catch (GeneralSecurityException e) {
                throw new IOException("文件加密失败");
            }
            filled = 0;
        }

        @Override
        public void flush() {
            // 段未写满前不输出，避免产生非末段的短段；关闭时统一写出
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                flushSegment(true);
                out.flush();
            } finally {
                out.close();
            }
        }
    }

    private final class DecryptingStream extends InputStream {
        private final InputStream in;
        private final byte[] header;
        private final byte[] aad;
        private final byte[] segment = new byte[SEGMENT_PLAIN_BYTES + TAG_BYTES];
        private byte[] plain = new byte[0];
        private int pos;
        private int index;
        private boolean finished;
        private int lookahead = -2; // -2 = 未读取；-1 = EOF

        DecryptingStream(InputStream in, byte[] header, byte[] aad) {
            this.in = in;
            this.header = header;
            this.aad = aad;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            while (pos == plain.length) {
                if (finished) {
                    return -1;
                }
                nextSegment();
            }
            int n = Math.min(len, plain.length - pos);
            System.arraycopy(plain, pos, b, off, n);
            pos += n;
            return n;
        }

        private void nextSegment() throws IOException {
            int length = 0;
            if (lookahead >= 0) {
                segment[length++] = (byte) lookahead;
            }
            while (length < segment.length) {
                int n = in.read(segment, length, segment.length - length);
                if (n == -1) {
                    break;
                }
                length += n;
            }
            boolean last;
            if (length < segment.length) {
                last = true;
            } else {
                lookahead = in.read();
                last = lookahead == -1;
            }
            if (length < TAG_BYTES) {
                throw new EOFException("加密文件被截断");
            }
            Cipher c = cipher(Cipher.DECRYPT_MODE, header, index++, last, aad);
            try {
                plain = c.doFinal(segment, 0, length);
            } catch (GeneralSecurityException e) {
                throw new IOException("文件解密失败：密文已损坏或密钥不匹配");
            }
            pos = 0;
            finished = last;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
