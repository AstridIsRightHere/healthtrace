package com.healthtrace.report.domain;

import java.util.Optional;

/**
 * 按文件头（魔数）识别的实际类型，不信任客户端声明的 Content-Type（docs/19 PHOTO.media_type）。
 */
public enum DetectedMediaType {
    JPEG("image/jpeg", true),
    PNG("image/png", true),
    HEIC("image/heic", false),
    PDF("application/pdf", false);

    /** 判断所需的最少文件头字节数。 */
    public static final int HEADER_BYTES = 12;

    private final String mimeType;
    /** 是否允许上传客户端压缩预览图（HEIC/PDF 浏览器普遍无法 Canvas 解码，只存原件）。 */
    private final boolean previewSupported;

    DetectedMediaType(String mimeType, boolean previewSupported) {
        this.mimeType = mimeType;
        this.previewSupported = previewSupported;
    }

    public String mimeType() {
        return mimeType;
    }

    public boolean previewSupported() {
        return previewSupported;
    }

    public static Optional<DetectedMediaType> detect(byte[] header, int length) {
        if (length >= 3 && u(header[0]) == 0xFF && u(header[1]) == 0xD8 && u(header[2]) == 0xFF) {
            return Optional.of(JPEG);
        }
        if (length >= 8 && u(header[0]) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G'
                && header[4] == 0x0D && header[5] == 0x0A && header[6] == 0x1A && header[7] == 0x0A) {
            return Optional.of(PNG);
        }
        if (length >= 5 && header[0] == '%' && header[1] == 'P' && header[2] == 'D' && header[3] == 'F'
                && header[4] == '-') {
            return Optional.of(PDF);
        }
        if (length >= 12 && header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p') {
            String brand = new String(header, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
            switch (brand) {
                case "heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1" -> {
                    return Optional.of(HEIC);
                }
                default -> {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private static int u(byte b) {
        return b & 0xFF;
    }
}
