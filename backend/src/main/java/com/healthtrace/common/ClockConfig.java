package com.healthtrace.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 统一时间来源。系统事件时间一律 UTC（docs/19 通用规则）；注入 Clock 便于测试过期逻辑。
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
