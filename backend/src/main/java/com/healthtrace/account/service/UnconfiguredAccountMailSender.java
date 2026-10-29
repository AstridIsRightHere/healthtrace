package com.healthtrace.account.service;

import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 非 dev 且未配置 spring.mail.host 时的占位实现：应用照常启动（不依赖邮件的功能可用），
 * 启动时告警，每次发信都记录错误并抛 HT-5003，调用方事务回滚，不会静默吞掉邮件。
 */
@Component
@Profile("!dev")
@ConditionalOnExpression("'${spring.mail.host:}'.isBlank()")
public class UnconfiguredAccountMailSender implements AccountMailSender {

    static final String NOT_CONFIGURED = "未配置邮件服务（spring.mail.host），无法发送账号邮件";

    private static final Logger log = LoggerFactory.getLogger(UnconfiguredAccountMailSender.class);

    public UnconfiguredAccountMailSender() {
        log.warn("{}；开通账号、重发验证邮件与密码恢复将失败", NOT_CONFIGURED);
    }

    @Override
    public void sendEmailVerification(String email, String token) {
        fail("邮箱验证");
    }

    @Override
    public void sendPasswordReset(String email, String token) {
        fail("密码恢复");
    }

    private static void fail(String purpose) {
        log.error("{}邮件未发送: {}", purpose, NOT_CONFIGURED);
        throw new ApiException(ErrorCode.MAIL_UNAVAILABLE);
    }
}
