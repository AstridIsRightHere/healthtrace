package com.healthtrace;

import org.junit.jupiter.api.Test;

/**
 * 骨架阶段占位测试：不加载 Spring 上下文，确保不连数据库也能编译与运行。
 * 接入 MySQL 集成测试（ADR-012）后再改为 @SpringBootTest。
 */
class HealthTraceApplicationTests {

    @Test
    void contextLoads() {
    }
}
