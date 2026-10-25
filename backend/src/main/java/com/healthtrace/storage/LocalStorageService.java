package com.healthtrace.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;

/**
 * 本地目录存储，仅用于开发/测试（healthtrace.storage.type=local）。
 * 根目录由 healthtrace.storage.local.root 指定，缺失时启动失败，不回退到默认目录。
 * <p>
 * 写入先落到同目录临时文件再原子移动，失败不留半个对象；
 * 所有键解析后必须仍位于根目录内。
 */
@Service
@ConditionalOnProperty(prefix = "healthtrace.storage", name = "type", havingValue = "local")
public class LocalStorageService implements StorageService {

    private static final int BUFFER_SIZE = 64 * 1024;

    private final Path root;

    public LocalStorageService(@Value("${healthtrace.storage.local.root}") String root) {
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("healthtrace.storage.local.root 未配置");
        }
        this.root = Path.of(root).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建本地存储根目录", e);
        }
    }

    @Override
    public StoredObject put(String objectKey, InputStream content, long byteSize, String mediaType) {
        Path target = resolve(objectKey);
        Path temp = null;
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                throw new StorageException("对象已存在: " + objectKey);
            }
            temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            long written;
            try (OutputStream out = Files.newOutputStream(temp)) {
                written = copy(content, out);
            }
            if (written != byteSize) {
                throw new StorageException("写入字节数与期望不符: " + objectKey);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            temp = null;
            return new StoredObject(objectKey, written, mediaType);
        } catch (IOException e) {
            throw new StorageException("写入对象失败: " + objectKey, e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 临时文件清理失败不覆盖原始异常
                }
            }
        }
    }

    @Override
    public InputStream open(String objectKey) {
        try {
            return Files.newInputStream(resolve(objectKey));
        } catch (NoSuchFileException e) {
            throw new StorageException("对象不存在: " + objectKey, e);
        } catch (IOException e) {
            throw new StorageException("读取对象失败: " + objectKey, e);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            Files.deleteIfExists(resolve(objectKey));
        } catch (IOException e) {
            throw new StorageException("删除对象失败: " + objectKey, e);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        return Files.isRegularFile(resolve(objectKey));
    }

    @Override
    public Optional<URI> createAccessUrl(String objectKey, Duration ttl) {
        ObjectKeys.requireValid(objectKey);
        // 本地文件不提供直链，调用方经鉴权后代理返回
        return Optional.empty();
    }

    private Path resolve(String objectKey) {
        ObjectKeys.requireValid(objectKey);
        Path resolved = root.resolve(objectKey).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IllegalArgumentException("objectKey 越出存储根目录");
        }
        return resolved;
    }

    private static long copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
            total += n;
        }
        return total;
    }
}
