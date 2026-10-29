package com.healthtrace.account.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 开发环境邮件实现：不真正发信，只把链接打印到本机日志，便于用合成账号走通流程。
 * 仅在 dev Profile 生效；其余环境使用 SMTP 实现，未配置邮件服务时发信直接失败（不静默降级）。
 * 收件人做掩码处理；dev 只使用合成数据（29-环境配置说明）。
 */
@Component
@Profile("dev")
public class DevLoggingAccountMailSender implements AccountMailSender {

    private static final Logger log = LoggerFactory.getLogger(DevLoggingAccountMailSender.class);

    @Override
    public void sendEmailVerification(String email, String token) {
        log.info("[DEV 邮件，未实际发送] 邮箱验证 to={} link=/verify-email?token={}", mask(email), token);
    }

    @Override
    public void sendPasswordReset(String email, String token) {
        log.info("[DEV 邮件，未实际发送] 密码恢复 to={} link=/reset-password?token={}", mask(email), token);
    }

    static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + (at >= 0 ? email.substring(at) : "");
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
