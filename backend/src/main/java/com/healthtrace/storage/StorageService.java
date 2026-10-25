package com.healthtrace.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * 私有对象存储抽象。业务层只持有 objectKey，不感知本地路径或云厂商细节，
 * 以便开发环境用本地目录、生产环境迁移到阿里云 OSS 等对象存储时业务代码不变。
 * <p>
 * objectKey 由业务层生成（如 {@code reports/{reportId}/{uuid}/original}），
 * 只允许小写字母、数字及 {@code / _ . -}，不得含个人信息。
 */
public interface StorageService {

    /**
     * 写入对象。实现须保证不留下半个对象（失败时目标键不存在），且不覆盖已存在的键。
     *
     * @param byteSize 期望字节数；实际写入字节数不符时失败
     */
    StoredObject put(String objectKey, InputStream content, long byteSize, String mediaType);

    /** 打开对象读取流，调用方负责关闭。对象不存在时抛出 {@link StorageException}。 */
    InputStream open(String objectKey);

    /** 幂等删除：对象不存在时不报错。 */
    void delete(String objectKey);

    boolean exists(String objectKey);

    /**
     * 生成短期直接访问链接（如 OSS 签名 URL）。
     * 不支持直链的实现（本地存储）返回 empty，由调用方经后端鉴权后代理流式返回。
     */
    Optional<URI> createAccessUrl(String objectKey, Duration ttl);
}
