package com.healthtrace.storage;

import java.util.regex.Pattern;

/** objectKey 格式校验，所有存储实现共用，防止路径穿越与不可移植的键。 */
public final class ObjectKeys {

    public static final int MAX_LENGTH = 512;

    private static final Pattern ALLOWED = Pattern.compile("[a-z0-9][a-z0-9_.-]*(/[a-z0-9][a-z0-9_.-]*)*");

    private ObjectKeys() {
    }

    public static String requireValid(String objectKey) {
        if (objectKey == null || objectKey.length() > MAX_LENGTH || !ALLOWED.matcher(objectKey).matches()) {
            throw new IllegalArgumentException("objectKey 格式无效");
        }
        for (String segment : objectKey.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("objectKey 格式无效");
            }
        }
        return objectKey;
    }
}
