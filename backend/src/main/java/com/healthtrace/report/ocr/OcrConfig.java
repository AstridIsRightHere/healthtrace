package com.healthtrace.report.ocr;

import com.healthtrace.common.security.FieldCipher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * OCR 基础设施：配置绑定（缺失密钥即启动失败）、原文加密器、异步执行器与调度。
 */
@Configuration
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(OcrProperties.class)
public class OcrConfig {

    public static final String OCR_EXECUTOR = "ocrExecutor";

    /** OCR 原文/原始结构化结果专用加密器，与邮箱密钥分离。 */
    @Bean
    public OcrTextCipher ocrTextCipher(OcrProperties properties) {
        return new OcrTextCipher(new FieldCipher(properties.encryptionKeyBytes()));
    }

    /** 有界线程池：外部 OCR 调用不占用请求线程；队列满时任务留在库中由调度补偿。 */
    @Bean(name = OCR_EXECUTOR)
    public Executor ocrExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ocr-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
