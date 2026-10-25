package com.healthtrace.account.service;

/**
 * 账号相关邮件出口。实现只负责投递，不得把令牌写入日志以外的持久位置；
 * 生产实现接入真实邮件服务前，非 dev 环境没有该 Bean，应用会拒绝启动。
 */
public interface AccountMailSender {

    /** 发送邮箱验证邮件，token 为一次性原始令牌（数据库只存摘要）。 */
    void sendEmailVerification(String email, String token);

    /** 发送密码恢复邮件，token 为一次性原始令牌（数据库只存摘要）。 */
    void sendPasswordReset(String email, String token);
}
