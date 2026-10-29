package com.healthtrace.account.service;

/**
 * 账号相关邮件出口。实现只负责投递，不得把令牌写入日志以外的持久位置；
 * 实现选择：dev 为 {@link DevLoggingAccountMailSender}；其余环境配置 spring.mail.host 时为
 * {@link SmtpAccountMailSender}，否则为 {@link UnconfiguredAccountMailSender}（发信时抛 HT-5003）。
 */
public interface AccountMailSender {

    /** 发送邮箱验证邮件，token 为一次性原始令牌（数据库只存摘要）。 */
    void sendEmailVerification(String email, String token);

    /** 发送密码恢复邮件，token 为一次性原始令牌（数据库只存摘要）。 */
    void sendPasswordReset(String email, String token);
}
