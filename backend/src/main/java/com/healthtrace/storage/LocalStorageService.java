package com.healthtrace.storage;

import com.healthtrace.common.security.FieldCipher;
import com.healthtrace.common.security.FileCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 本地目录存储（healthtrace.storage.type=local），开发、测试与单机部署使用。
 * 根目录由 healthtrace.storage.local.root 指定，缺失时启动失败，不回退到默认目录。
 * <p>
 * 应用层加密：每个对象在写入磁盘前用 {@link FileCipher}（AES-256-GCM 分段，密钥
 * HT_STORAGE_ENCRYPTION_KEY）加密，读取时解密后流式返回。磁盘上只有密文，直接打开存储目录看不到照片内容；
 * 密文与对象键绑定，复制到其他键下无法解密。
 * <p>
 * 写入先落到同目录临时文件再原子移动，失败不留半个对象；所有键解析后必须仍位于根目录内。
 */
@Service
@ConditionalOnProperty(prefix = "healthtrace.storage", name = "type", havingValue = "local")
public class LocalStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalStorageService.class);

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final String TEMP_PREFIX = ".upload-";

    private final Path root;
    private final FileCipher cipher;

    public LocalStorageService(@Value("${healthtrace.storage.local.root}") String root,
                               @Value("${healthtrace.storage.encryption-key:}") String encryptionKey) {
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("healthtrace.storage.local.root 未配置");
        }
        this.cipher = new FileCipher(FieldCipher.decodeKey("HT_STORAGE_ENCRYPTION_KEY", encryptionKey));
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
            temp = Files.createTempFile(target.getParent(), TEMP_PREFIX, ".tmp");
            long written;
            try (OutputStream out = cipher.encrypt(Files.newOutputStream(temp), objectKey)) {
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
            deleteQuietly(temp, objectKey);
        }
    }

    @Override
    public InputStream open(String objectKey) {
        InputStream raw = null;
        try {
            raw = new BufferedInputStream(Files.newInputStream(resolve(objectKey)), BUFFER_SIZE);
            InputStream plain = cipher.decrypt(raw, objectKey);
            return plain;
        } catch (NoSuchFileException e) {
            throw new StorageException("对象不存在: " + objectKey, e);
        } catch (IOException e) {
            closeQuietly(raw);
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

    /**
     * 启动完成后把文件加密上线前写入的明文对象就地加密，保证存储目录里不再有明文照片。
     * 幂等：已加密的文件按魔数跳过。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void migrateLegacyObjectsOnStartup() {
        int migrated = encryptLegacyPlaintextObjects();
        if (migrated > 0) {
            log.info("已加密遗留明文存储对象 count={}", migrated);
        }
    }

    /**
     * 把加密上线前遗留的明文对象就地加密（幂等：已加密的文件跳过）。
     * 逐个文件写临时密文再原子替换，中途失败不破坏原文件。返回本次加密的文件数。
     */
    public int encryptLegacyPlaintextObjects() {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith(TEMP_PREFIX))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("扫描存储目录失败", e);
        }
        int migrated = 0;
        for (Path file : files) {
            String objectKey = root.relativize(file).toString().replace('\\', '/');
            try {
                ObjectKeys.requireValid(objectKey);
            } catch (IllegalArgumentException notAnObject) {
                continue; // 不是本服务写入的对象（如系统文件），不处理
            }
            if (encryptInPlaceIfPlaintext(file, objectKey)) {
                migrated++;
            }
        }
        return migrated;
    }

    private boolean encryptInPlaceIfPlaintext(Path file, String objectKey) {
        Path temp = null;
        try {
            byte[] head;
            try (InputStream in = Files.newInputStream(file)) {
                head = in.readNBytes(4);
            }
            if (FileCipher.hasMagic(head)) {
                return false;
            }
            temp = Files.createTempFile(file.getParent(), TEMP_PREFIX, ".tmp");
            try (InputStream in = Files.newInputStream(file);
                 OutputStream out = cipher.encrypt(Files.newOutputStream(temp), objectKey)) {
                copy(in, out);
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temp = null;
            return true;
        } catch (IOException e) {
            throw new StorageException("加密遗留对象失败: " + objectKey, e);
        } finally {
            deleteQuietly(temp, objectKey);
        }
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

    private static void deleteQuietly(Path temp, String objectKey) {
        if (temp == null) {
            return;
        }
        try {
            Files.deleteIfExists(temp);
        } catch (IOException cleanupFailure) {
            // 临时文件清理失败不覆盖原始异常，只留调试日志（对象键不含个人信息）
            log.debug("临时文件清理失败 objectKey={} temp={}", objectKey, temp, cleanupFailure);
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // 已在处理读取失败，关闭失败不再上抛
            }
        }
    }
}
