package com.healthtrace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * HealthTrace 模块化单体启动类（ADR-002）。
 */
@SpringBootApplication
public class HealthTraceApplication {

    public static void main(String[] args) {
        SpringApplication.run(HealthTraceApplication.class, args);
    }
}
