package com.healthtrace.account.service;

import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 邮件实现按 Profile 与 spring.mail.host 选择：dev 日志、配置了主机走 SMTP、否则占位实现（启动成功、发信失败）。 */
class AccountMailSenderSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DevLoggingAccountMailSender.class, SmtpAccountMailSender.class,
                    UnconfiguredAccountMailSender.class)
            .withBean(JavaMailSender.class, () -> mock(JavaMailSender.class));

    @Test
    void devProfileUsesLoggingSender() {
        runner.withPropertyValues("spring.profiles.active=dev", "spring.mail.host=smtp.example.test")
                .run(ctx -> assertThat(ctx).getBean(AccountMailSender.class)
                        .isInstanceOf(DevLoggingAccountMailSender.class));
    }

    @Test
    void mailHostSelectsSmtpSender() {
        runner.withPropertyValues("spring.profiles.active=test", "spring.mail.host=smtp.example.test",
                        "healthtrace.mail.from=no-reply@example.test",
                        "healthtrace.mail.link-base-url=https://app.example.test/")
                .run(ctx -> assertThat(ctx).getBean(AccountMailSender.class)
                        .isInstanceOf(SmtpAccountMailSender.class));
    }

    @Test
    void smtpWithoutFromOrLinkBaseFailsStartup() {
        runner.withPropertyValues("spring.profiles.active=test", "spring.mail.host=smtp.example.test",
                        "healthtrace.mail.from=", "healthtrace.mail.link-base-url=")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("healthtrace.mail.from"));
    }

    @Test
    void noMailHostStartsWithUnconfiguredSenderThatFailsLoudly() {
        runner.withPropertyValues("spring.profiles.active=test")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    AccountMailSender sender = ctx.getBean(AccountMailSender.class);
                    assertThat(sender).isInstanceOf(UnconfiguredAccountMailSender.class);
                    assertThatThrownBy(() -> sender.sendEmailVerification("a@example.test", "t"))
                            .isInstanceOfSatisfying(ApiException.class,
                                    ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MAIL_UNAVAILABLE));
                    assertThatThrownBy(() -> sender.sendPasswordReset("a@example.test", "t"))
                            .isInstanceOf(ApiException.class);
                });
    }

    @Test
    void smtpSenderBuildsVerificationAndResetMessages() {
        JavaMailSender javaMail = mock(JavaMailSender.class);
        SmtpAccountMailSender sender = new SmtpAccountMailSender(javaMail, "no-reply@example.test",
                "https://app.example.test/");

        sender.sendEmailVerification("user@example.test", "abc+/=");
        sender.sendPasswordReset("user@example.test", "xyz");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMail, org.mockito.Mockito.times(2)).send(captor.capture());
        SimpleMailMessage verification = captor.getAllValues().get(0);
        assertThat(verification.getFrom()).isEqualTo("no-reply@example.test");
        assertThat(verification.getTo()).containsExactly("user@example.test");
        assertThat(verification.getText())
                .contains("https://app.example.test/verify-email?token=abc%2B%2F%3D");
        assertThat(captor.getAllValues().get(1).getText())
                .contains("https://app.example.test/reset-password?token=xyz");
    }

    @Test
    void smtpDeliveryFailureBecomesRetryableMailUnavailable() {
        JavaMailSender javaMail = mock(JavaMailSender.class);
        doThrow(new MailSendException("connection refused to user@example.test"))
                .when(javaMail).send(any(SimpleMailMessage.class));
        SmtpAccountMailSender sender = new SmtpAccountMailSender(javaMail, "no-reply@example.test",
                "https://app.example.test");

        assertThatThrownBy(() -> sender.sendPasswordReset("user@example.test", "xyz"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MAIL_UNAVAILABLE);
                    assertThat(ex.getErrorCode().retryable()).isTrue();
                });
    }
}
