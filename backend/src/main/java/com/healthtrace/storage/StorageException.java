package com.healthtrace.storage;

/** 存储层失败。消息只含错误类别与 objectKey，不含文件内容或个人信息。 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
