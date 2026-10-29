package com.healthtrace.account.service;

import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 非 dev 环境的真实邮件实现：配置了 spring.mail.host 时启用，经 Boot 自动配置的 JavaMailSender 走 SMTP 投递。
 * <ul>
 *   <li>发件人 healthtrace.mail.from 与前端地址 healthtrace.mail.link-base-url 只从环境变量注入，缺失时启动失败。</li>
 *   <li>投递失败抛 HT-5003（可重试），调用方事务随之回滚，令牌不会在用户收不到邮件时悄悄生效。</li>
 *   <li>日志不记录收件人与令牌，只记录用途和异常类型。</li>
 * </ul>
 */
@Component
@Profile("!dev")
@ConditionalOnExpression("!'${spring.mail.host:}'.isBlank()")
public class SmtpAccountMailSender implements AccountMailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpAccountMailSender.class);

    private final JavaMailSender mailSender;
    private final String from;
    private final String linkBaseUrl;

    public SmtpAccountMailSender(JavaMailSender mailSender,
                                 @Value("${healthtrace.mail.from}") String from,
                                 @Value("${healthtrace.mail.link-base-url}") String linkBaseUrl) {
        if (from.isBlank() || linkBaseUrl.isBlank()) {
            throw new IllegalStateException("启用 SMTP 时必须配置 healthtrace.mail.from 与 healthtrace.mail.link-base-url");
        }
        this.mailSender = mailSender;
        this.from = from;
        this.linkBaseUrl = linkBaseUrl.endsWith("/") ? linkBaseUrl.substring(0, linkBaseUrl.length() - 1) : linkBaseUrl;
    }

    @Override
    public void sendEmailVerification(String email, String token) {
        send(email, "【HealthTrace】请验证您的邮箱",
                "您好：\n\n请打开以下链接完成邮箱验证：\n" + link("/verify-email", token)
                        + "\n\n如果这不是您本人的操作，请忽略本邮件。", "邮箱验证");
    }

    @Override
    public void sendPasswordReset(String email, String token) {
        send(email, "【HealthTrace】重置密码",
                "您好：\n\n请打开以下链接设置新密码：\n" + link("/reset-password", token)
                        + "\n\n如果这不是您本人的操作，请忽略本邮件，您的密码不会改变。", "密码恢复");
    }

    String link(String path, String token) {
        return linkBaseUrl + path + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    private void send(String to, String subject, String text, String purpose) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        try {
            mailSender.send(message);
        } catch (MailException ex) {
            // 不记录收件人、令牌与异常消息（可能回显地址），只记录类型
            log.warn("{}邮件投递失败: {}", purpose, ex.getClass().getSimpleName());
            throw new ApiException(ErrorCode.MAIL_UNAVAILABLE);
        }
    }
}
