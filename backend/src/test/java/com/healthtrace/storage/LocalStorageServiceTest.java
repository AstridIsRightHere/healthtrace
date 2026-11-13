package com.healthtrace.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 本地存储：原子写入、拒绝覆盖、字节数校验、路径穿越防护。 */
class LocalStorageServiceTest {

    @TempDir
    Path root;

    private static final String KEY = "c3RvcmFnZS1maWxlLWtleS0wMTIzNDU2Nzg5YWJjZGU=";

    private LocalStorageService storage;

    @BeforeEach
    void setUp() {
        storage = new LocalStorageService(root.toString(), KEY);
    }

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void putThenOpenRoundTrips() throws Exception {
        StoredObject stored = storage.put("profiles/1/reports/2/a.jpg", bytes("hello"), 5, "image/jpeg");

        assertThat(stored.byteSize()).isEqualTo(5);
        assertThat(storage.exists("profiles/1/reports/2/a.jpg")).isTrue();
        try (InputStream in = storage.open("profiles/1/reports/2/a.jpg")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
        }
    }

    @Test
    void refusesToOverwriteExistingObject() {
        storage.put("k/a.jpg", bytes("one"), 3, "image/jpeg");

        assertThatThrownBy(() -> storage.put("k/a.jpg", bytes("two"), 3, "image/jpeg"))
                .isInstanceOf(StorageException.class);
    }

    @Test
    void byteCountMismatchLeavesNoObjectOrTempFile() throws Exception {
        assertThatThrownBy(() -> storage.put("k/b.jpg", bytes("abc"), 10, "image/jpeg"))
                .isInstanceOf(StorageException.class);

        assertThat(storage.exists("k/b.jpg")).isFalse();
        try (var files = Files.list(root.resolve("k"))) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void rejectsPathTraversalAndInvalidKeys() {
        assertThatThrownBy(() -> storage.put("../escape.jpg", bytes("x"), 1, "image/jpeg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.open("a/../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.put("/abs.jpg", bytes("x"), 1, "image/jpeg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.put("A\\b.jpg", bytes("x"), 1, "image/jpeg"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteIsIdempotent() {
        storage.put("k/c.jpg", bytes("x"), 1, "image/jpeg");

        storage.delete("k/c.jpg");
        storage.delete("k/c.jpg");

        assertThat(storage.exists("k/c.jpg")).isFalse();
    }

    @Test
    void localStorageHasNoDirectAccessUrl() {
        assertThat(storage.createAccessUrl("k/c.jpg", Duration.ofMinutes(5))).isEmpty();
    }

    @Test
    void blankRootFailsFast() {
        assertThatThrownBy(() -> new LocalStorageService(" ", KEY)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingOrShortEncryptionKeyFailsFast() {
        assertThatThrownBy(() -> new LocalStorageService(root.toString(), ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HT_STORAGE_ENCRYPTION_KEY");
        assertThatThrownBy(() -> new LocalStorageService(root.toString(), "c2hvcnQ="))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fileOnDiskIsCiphertextNotPlaintext() throws Exception {
        byte[] jpeg = new byte[200_000];
        new java.util.Random(7).nextBytes(jpeg);
        jpeg[0] = (byte) 0xFF;
        jpeg[1] = (byte) 0xD8;
        jpeg[2] = (byte) 0xFF;
        String marker = "PATIENT-NAME-张三";
        byte[] markerBytes = marker.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(markerBytes, 0, jpeg, 1000, markerBytes.length);

        storage.put("reports/9/x/original", new ByteArrayInputStream(jpeg), jpeg.length, "image/jpeg");

        byte[] onDisk = Files.readAllBytes(root.resolve("reports/9/x/original"));
        assertThat(onDisk).startsWith('H', 'T', 'E', '1');
        assertThat(onDisk[4] == (byte) 0xFF && onDisk[5] == (byte) 0xD8).isFalse();
        assertThat(new String(onDisk, StandardCharsets.ISO_8859_1))
                .doesNotContain(new String(markerBytes, StandardCharsets.ISO_8859_1));
        assertThat(onDisk.length).isEqualTo(com.healthtrace.common.security.FileCipher.ciphertextLength(jpeg.length));
        try (InputStream in = storage.open("reports/9/x/original")) {
            assertThat(in.readAllBytes()).isEqualTo(jpeg);
        }
    }

    @Test
    void wrongKeyCannotReadObject() {
        storage.put("k/secret.jpg", bytes("secret-bytes"), 12, "image/jpeg");
        LocalStorageService other = new LocalStorageService(root.toString(),
                "b3RoZXIta2V5LTAxMjM0NTY3ODlhYmNkZWYwMTIzNDU=");

        assertThatThrownBy(() -> {
            try (InputStream in = other.open("k/secret.jpg")) {
                in.readAllBytes();
            }
        }).isInstanceOf(Exception.class);
    }

    @Test
    void ciphertextMovedToAnotherKeyCannotBeRead() throws Exception {
        storage.put("k/a1.jpg", bytes("photo-a"), 7, "image/jpeg");
        Files.createDirectories(root.resolve("k2"));
        Files.copy(root.resolve("k/a1.jpg"), root.resolve("k2/a1.jpg"));

        assertThatThrownBy(() -> {
            try (InputStream in = storage.open("k2/a1.jpg")) {
                in.readAllBytes();
            }
        }).isInstanceOf(Exception.class);
    }

    @Test
    void legacyPlaintextObjectsAreEncryptedInPlaceIdempotently() throws Exception {
        Path legacy = root.resolve("reports/1/old/original");
        Files.createDirectories(legacy.getParent());
        Files.write(legacy, "legacy-plain-photo".getBytes(StandardCharsets.UTF_8));
        storage.put("reports/1/new/original", bytes("new"), 3, "image/jpeg");
        byte[] alreadyEncrypted = Files.readAllBytes(root.resolve("reports/1/new/original"));

        assertThat(storage.encryptLegacyPlaintextObjects()).isEqualTo(1);
        assertThat(storage.encryptLegacyPlaintextObjects()).isZero();

        assertThat(Files.readAllBytes(legacy)).startsWith('H', 'T', 'E', '1');
        assertThat(Files.readAllBytes(root.resolve("reports/1/new/original"))).isEqualTo(alreadyEncrypted);
        try (InputStream in = storage.open("reports/1/old/original")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("legacy-plain-photo");
        }
    }
}
