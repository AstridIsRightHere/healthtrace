package com.healthtrace.rights.deletion;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 删除基础设施：存储清理专用的有界线程池，不占用请求线程与 OCR 线程池。
 * 队列满时丢弃，残留任务行由 {@link StoragePurgeService#retryDue()} 补偿。
 */
@Configuration(proxyBeanMethods = false)
public class DeletionConfig {

    public static final String PURGE_EXECUTOR = "storagePurgeExecutor";

    @Bean(name = PURGE_EXECUTOR)
    public Executor storagePurgeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("purge-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
