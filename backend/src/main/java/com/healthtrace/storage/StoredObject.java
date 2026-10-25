package com.healthtrace.storage;

/** 已写入对象的元数据。 */
public record StoredObject(String objectKey, long byteSize, String mediaType) {
}
