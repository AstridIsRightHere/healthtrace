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

    private LocalStorageService storage;

    @BeforeEach
    void setUp() {
        storage = new LocalStorageService(root.toString());
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
        assertThatThrownBy(() -> new LocalStorageService(" ")).isInstanceOf(IllegalStateException.class);
    }
}
