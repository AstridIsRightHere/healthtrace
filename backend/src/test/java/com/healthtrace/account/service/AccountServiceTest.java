package com.healthtrace.account.service;

import com.healthtrace.account.domain.Account;
import com.healthtrace.account.domain.AccountStatus;
import com.healthtrace.account.domain.AuthPolicy;
import com.healthtrace.account.domain.AuthToken;
import com.healthtrace.account.domain.Invitation;
import com.healthtrace.account.domain.TokenPurpose;
import com.healthtrace.account.mapper.AccountMapper;
import com.healthtrace.account.mapper.AuthTokenMapper;
import com.healthtrace.account.mapper.InvitationMapper;
import com.healthtrace.common.security.EmailProtector;
import com.healthtrace.common.security.OpaqueTokens;
import com.healthtrace.common.security.SecretKeyProperties;
import com.healthtrace.common.security.SecurityConfig;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AccountService 单元测试：不连数据库、不发真实邮件（docs/22）。
 * 覆盖 TC-001/TC-002 的反例：邀请过期/已用、令牌重放/过期/用途不符、连续失败锁定。
 */
class AccountServiceTest {

    private static final Instant NOW_INSTANT = Instant.parse("2026-10-25T10:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);
    private static final String EMAIL = "synthetic.user@example.test";
    private static final String PASSWORD = "correct horse battery";

    private AccountMapper accountMapper;
    private InvitationMapper invitationMapper;
    private AuthTokenMapper authTokenMapper;
    private AccountMailSender mailSender;
    private EmailProtector emailProtector;
    private PasswordEncoder passwordEncoder;
    private AccountService service;

    @BeforeEach
    void setUp() {
        accountMapper = mock(AccountMapper.class);
        invitationMapper = mock(InvitationMapper.class);
        authTokenMapper = mock(AuthTokenMapper.class);
        mailSender = mock(AccountMailSender.class);
        emailProtector = new EmailProtector(testKeys());
        // 单元测试用低成本以加快速度；生产成本 12 由 SecurityConfigCostTest 覆盖
        passwordEncoder = new BCryptPasswordEncoder(4);
        service = new AccountService(accountMapper, invitationMapper, authTokenMapper, emailProtector,
                passwordEncoder, mailSender, Clock.fixed(NOW_INSTANT, ZoneOffset.UTC));
    }

    static SecretKeyProperties testKeys() {
        byte[] enc = new byte[32];
        byte[] idx = new byte[32];
        for (int i = 0; i < 32; i++) {
            enc[i] = (byte) i;
            idx[i] = (byte) (100 + i);
        }
        return new SecretKeyProperties(Base64.getEncoder().encodeToString(enc),
                Base64.getEncoder().encodeToString(idx));
    }

    @Test
    void productionPasswordEncoderUsesCost12() {
        String hash = new SecurityConfig().passwordEncoder().encode("x");
        assertThat(hash).startsWith("$2a$" + AuthPolicy.BCRYPT_COST + "$");
    }

    @Nested
    class Register {

        private Invitation invitation(LocalDateTime expiresAt, LocalDateTime usedAt) {
            Invitation inv = new Invitation();
            inv.setId(7L);
            inv.setIssuerAccountId(1L);
            inv.setExpiresAt(expiresAt);
            inv.setUsedAt(usedAt);
            return inv;
        }

        @Test
        void validInvitationCreatesUnverifiedAccountAndSendsVerificationMail() {
            when(invitationMapper.findByTokenDigest(OpaqueTokens.digest("inv-code")))
                    .thenReturn(invitation(NOW.plusDays(1), null));
            when(accountMapper.insert(any())).thenAnswer(call -> {
                call.<Account>getArgument(0).setId(42L);
                return 1;
            });
            when(invitationMapper.markUsed(7L, 42L, NOW)).thenReturn(1);

            AccountView view = service.register("inv-code", "  Synthetic.User@Example.TEST ", PASSWORD);

            assertThat(view.id()).isEqualTo(42L);
            assertThat(view.email()).isEqualTo(EMAIL);
            assertThat(view.emailVerified()).isFalse();
            assertThat(view.status()).isEqualTo(AccountStatus.UNVERIFIED);

            ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
            verify(accountMapper).insert(saved.capture());
            assertThat(saved.getValue().getEmailIndex()).isEqualTo(emailProtector.index(EMAIL));
            assertThat(saved.getValue().getPasswordHash()).isNotEqualTo(PASSWORD);
            assertThat(passwordEncoder.matches(PASSWORD, saved.getValue().getPasswordHash())).isTrue();

            ArgumentCaptor<AuthToken> token = ArgumentCaptor.forClass(AuthToken.class);
            verify(authTokenMapper).insert(token.capture());
            assertThat(token.getValue().getPurpose()).isEqualTo(TokenPurpose.EMAIL_VERIFICATION);
            assertThat(token.getValue().getExpiresAt()).isEqualTo(NOW.plusHours(24));

            // 邮件中的原始令牌与库中摘要对应，库里不存原令牌
            ArgumentCaptor<String> rawToken = ArgumentCaptor.forClass(String.class);
            verify(mailSender).sendEmailVerification(eq(EMAIL), rawToken.capture());
            assertThat(token.getValue().getTokenDigest()).isEqualTo(OpaqueTokens.digest(rawToken.getValue()));
            assertThat(token.getValue().getTokenDigest()).isNotEqualTo(rawToken.getValue());
        }

        @Test
        void expiredInvitationIsRejected() {
            when(invitationMapper.findByTokenDigest(anyString())).thenReturn(invitation(NOW.minusSeconds(1), null));

            assertCode(() -> service.register("inv-code", EMAIL, PASSWORD), ErrorCode.INVALID_CREDENTIAL);
            verify(accountMapper, never()).insert(any());
            verifyNoInteractions(mailSender);
        }

        @Test
        void usedInvitationIsRejected() {
            when(invitationMapper.findByTokenDigest(anyString()))
                    .thenReturn(invitation(NOW.plusDays(1), NOW.minusHours(1)));

            assertCode(() -> service.register("inv-code", EMAIL, PASSWORD), ErrorCode.INVALID_CREDENTIAL);
            verify(accountMapper, never()).insert(any());
        }

        @Test
        void unknownInvitationIsRejected() {
            assertCode(() -> service.register("nope", EMAIL, PASSWORD), ErrorCode.INVALID_CREDENTIAL);
        }

        @Test
        void concurrentInvitationUseLosesTheRace() {
            when(invitationMapper.findByTokenDigest(anyString())).thenReturn(invitation(null, null));
            when(accountMapper.insert(any())).thenAnswer(call -> {
                call.<Account>getArgument(0).setId(43L);
                return 1;
            });
            when(invitationMapper.markUsed(anyLong(), anyLong(), any())).thenReturn(0);

            assertCode(() -> service.register("inv-code", EMAIL, PASSWORD), ErrorCode.INVALID_CREDENTIAL);
            verifyNoInteractions(mailSender);
        }

        @Test
        void existingEmailIsRejected() {
            when(invitationMapper.findByTokenDigest(anyString())).thenReturn(invitation(null, null));
            when(accountMapper.findByEmailIndex(emailProtector.index(EMAIL))).thenReturn(new Account());

            assertCode(() -> service.register("inv-code", EMAIL, PASSWORD), ErrorCode.OPERATION_NOT_ALLOWED);
        }

        @Test
        void passwordShorterThan12CharactersIsRejected() {
            assertCode(() -> service.register("inv-code", EMAIL, "elevenchars"), ErrorCode.VALIDATION_FAILED);
            verifyNoInteractions(invitationMapper);
        }

        @Test
        void passwordOf12CharactersWithoutComplexityIsAccepted() {
            AccountService.requireAcceptablePassword("password", "aaaaaaaaaaaa");
        }

        @Test
        void passwordOver72BytesIsRejectedInsteadOfTruncated() {
            assertCode(() -> AccountService.requireAcceptablePassword("password", "a".repeat(73)),
                    ErrorCode.VALIDATION_FAILED);
        }
    }

    @Nested
    class Tokens {

        private AuthToken token(TokenPurpose purpose, LocalDateTime expiresAt, LocalDateTime consumedAt) {
            AuthToken t = new AuthToken();
            t.setId(9L);
            t.setAccountId(42L);
            t.setPurpose(purpose);
            t.setExpiresAt(expiresAt);
            t.setConsumedAt(consumedAt);
            return t;
        }

        @Test
        void verificationTokenActivatesAccount() {
            when(authTokenMapper.findByTokenDigest(OpaqueTokens.digest("tok")))
                    .thenReturn(token(TokenPurpose.EMAIL_VERIFICATION, NOW.plusHours(1), null));
            when(authTokenMapper.consume(9L, NOW)).thenReturn(1);
            when(accountMapper.markEmailVerified(42L, NOW, AccountStatus.UNVERIFIED, AccountStatus.ACTIVE))
                    .thenReturn(1);

            service.confirmEmailVerification("tok");

            verify(accountMapper).markEmailVerified(42L, NOW, AccountStatus.UNVERIFIED, AccountStatus.ACTIVE);
        }

        @Test
        void consumedTokenCannotBeReused() {
            when(authTokenMapper.findByTokenDigest(anyString()))
                    .thenReturn(token(TokenPurpose.EMAIL_VERIFICATION, NOW.plusHours(1), NOW.minusMinutes(5)));

            assertCode(() -> service.confirmEmailVerification("tok"), ErrorCode.INVALID_CREDENTIAL);
            verify(authTokenMapper, never()).consume(anyLong(), any());
            verify(accountMapper, never()).markEmailVerified(anyLong(), any(), any(), any());
        }

        @Test
        void concurrentReuseLosesConditionalUpdate() {
            when(authTokenMapper.findByTokenDigest(anyString()))
                    .thenReturn(token(TokenPurpose.PASSWORD_RESET, NOW.plusMinutes(30), null));
            when(authTokenMapper.consume(9L, NOW)).thenReturn(0);

            assertCode(() -> service.resetPassword("tok", PASSWORD), ErrorCode.INVALID_CREDENTIAL);
            verify(accountMapper, never()).updatePasswordHash(anyLong(), anyString(), any());
        }

        @Test
        void expiredResetTokenIsRejected() {
            when(authTokenMapper.findByTokenDigest(anyString()))
                    .thenReturn(token(TokenPurpose.PASSWORD_RESET, NOW, null));

            assertCode(() -> service.resetPassword("tok", PASSWORD), ErrorCode.INVALID_CREDENTIAL);
        }

        @Test
        void tokenWithWrongPurposeIsRejected() {
            when(authTokenMapper.findByTokenDigest(anyString()))
                    .thenReturn(token(TokenPurpose.EMAIL_VERIFICATION, NOW.plusHours(1), null));

            assertCode(() -> service.resetPassword("tok", PASSWORD), ErrorCode.INVALID_CREDENTIAL);
            verify(authTokenMapper, never()).consume(anyLong(), any());
        }

        @Test
        void resetConsumesTokenAndInvalidatesOthers() {
            when(authTokenMapper.findByTokenDigest(anyString()))
                    .thenReturn(token(TokenPurpose.PASSWORD_RESET, NOW.plusMinutes(30), null));
            when(authTokenMapper.consume(9L, NOW)).thenReturn(1);

            service.resetPassword("tok", "a brand new passphrase");

            ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
            verify(accountMapper).updatePasswordHash(eq(42L), hash.capture(), eq(NOW));
            assertThat(passwordEncoder.matches("a brand new passphrase", hash.getValue())).isTrue();
            verify(authTokenMapper).invalidateOutstanding(42L, TokenPurpose.PASSWORD_RESET, NOW);
        }
    }

    @Nested
    class PasswordResetRequest {

        @Test
        void verifiedActiveAccountGetsOneHourToken() {
            Account account = account(AccountStatus.ACTIVE, NOW.minusDays(1));
            when(accountMapper.findByEmailIndex(emailProtector.index(EMAIL))).thenReturn(account);

            service.requestPasswordReset(EMAIL);

            ArgumentCaptor<AuthToken> token = ArgumentCaptor.forClass(AuthToken.class);
            verify(authTokenMapper).insert(token.capture());
            assertThat(token.getValue().getPurpose()).isEqualTo(TokenPurpose.PASSWORD_RESET);
            assertThat(token.getValue().getExpiresAt()).isEqualTo(NOW.plusHours(1));
            verify(authTokenMapper).invalidateOutstanding(42L, TokenPurpose.PASSWORD_RESET, NOW);
            verify(mailSender).sendPasswordReset(eq(EMAIL), anyString());
        }

        @Test
        void unverifiedAccountGetsNoMail() {
            when(accountMapper.findByEmailIndex(anyString())).thenReturn(account(AccountStatus.UNVERIFIED, null));

            service.requestPasswordReset(EMAIL);

            verifyNoInteractions(mailSender);
            verify(authTokenMapper, never()).insert(any());
        }

        @Test
        void unknownEmailIsSilentlyIgnored() {
            service.requestPasswordReset("nobody@example.test");

            verifyNoInteractions(mailSender, authTokenMapper);
        }
    }

    @Nested
    class Login {

        @Test
        void correctPasswordResetsFailureCounter() {
            Account account = account(AccountStatus.ACTIVE, NOW.minusDays(1));
            account.setFailedLoginCount(3);
            when(accountMapper.findByEmailIndex(anyString())).thenReturn(account);

            assertThat(service.authenticate(EMAIL, PASSWORD).accountId()).isEqualTo(42L);
            verify(accountMapper).resetLoginFailures(42L, NOW);
        }

        @Test
        void wrongPasswordIncrementsCounter() {
            Account account = account(AccountStatus.ACTIVE, NOW.minusDays(1));
            account.setFailedLoginCount(2);
            when(accountMapper.findByEmailIndex(anyString())).thenReturn(account);

            assertCode(() -> service.authenticate(EMAIL, "wrong password!!"), ErrorCode.BAD_CREDENTIALS);
            verify(accountMapper).updateLoginFailure(42L, 3, null, NOW);
        }

        @Test
        void fifthConsecutiveFailureLocksFor15Minutes() {
            Account account = account(AccountStatus.ACTIVE, NOW.minusDays(1));
            account.setFailedLoginCount(AuthPolicy.MAX_FAILED_LOGINS - 1);
            when(accountMapper.findByEmailIndex(anyString())).thenReturn(account);

            assertCode(() -> service.authenticate(EMAIL, "wrong password!!"), ErrorCode.TOO_MANY_REQUESTS);
            verify(accountMapper).updateLoginFailure(42L, 5, NOW.plusMinutes(15), NOW);
        }

        @Test
        void lockedAccountRejectsEvenCorrectPassword() {
            Account account = account(AccountStatus.ACTIVE, NOW.minusDays(1));
            account.setFailedLoginCount(5);
            account.setLockedUntil(NOW.plusMinutes(1));
            when(accountMapper.findByEmailIndex(anyString())).thenReturn(account);

            assertCode(() -> service.authenticate(EMAIL, PASSWORD), ErrorCode.TOO_MANY_REQUESTS);
            verify(accountMapper, never()).resetLoginFailures(anyLong(), any());
            verify(accountMapper, never()).updateLoginFailure(anyLong(), anyInt(), any(), any());
        }

        @Test
        void expiredLockStartsCountingAgain() {
            Account account = account(AccountStatus.ACTIVE, NOW.minusDays(1));
            account.setFailedLoginCount(5);
            account.setLockedUntil(NOW.minusSeconds(1));
            when(accountMapper.findByEmailIndex(anyString())).thenReturn(account);

            assertCode(() -> service.authenticate(EMAIL, "wrong password!!"), ErrorCode.BAD_CREDENTIALS);
            verify(accountMapper).updateLoginFailure(42L, 1, null, NOW);
        }

        @Test
        void unknownEmailGivesSameErrorAsWrongPassword() {
            assertCode(() -> service.authenticate("nobody@example.test", PASSWORD), ErrorCode.BAD_CREDENTIALS);
        }

        @Test
        void frozenAccountCannotLogIn() {
            when(accountMapper.findByEmailIndex(anyString()))
                    .thenReturn(account(AccountStatus.FROZEN, NOW.minusDays(1)));

            assertCode(() -> service.authenticate(EMAIL, PASSWORD), ErrorCode.BAD_CREDENTIALS);
        }
    }

    private Account account(AccountStatus status, LocalDateTime verifiedAt) {
        Account account = new Account();
        account.setId(42L);
        account.setEmailCiphertext(emailProtector.encrypt(EMAIL));
        account.setEmailIndex(emailProtector.index(EMAIL));
        account.setPasswordHash(passwordEncoder.encode(PASSWORD));
        account.setStatus(status);
        account.setEmailVerifiedAt(verifiedAt);
        return account;
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(expected);
    }
}
