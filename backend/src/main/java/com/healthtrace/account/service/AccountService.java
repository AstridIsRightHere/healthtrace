package com.healthtrace.account.service;

import com.healthtrace.account.domain.Account;
import com.healthtrace.account.domain.AccountPrincipal;
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
import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.FieldErrorItem;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 账号开通、邮箱验证、登录与密码找回（FR-001/FR-002，US-001/US-002，DEC-017）。
 * <ul>
 *   <li>邀请码与令牌只按 SHA-256 摘要查询，一次性消耗用条件更新保证并发下只成功一次。</li>
 *   <li>令牌失效原因（不存在/过期/已用/用途不符）统一返回 HT-2006，不区分。</li>
 *   <li>连续 5 次密码错误锁定 15 分钟；锁定状态持久化在 account 表，重启不丢失。</li>
 * </ul>
 */
@Service
public class AccountService {

    private final AccountMapper accountMapper;
    private final InvitationMapper invitationMapper;
    private final AuthTokenMapper authTokenMapper;
    private final EmailProtector emailProtector;
    private final PasswordEncoder passwordEncoder;
    private final AccountMailSender mailSender;
    private final Clock clock;
    /** 账号不存在时也做一次同成本的 BCrypt 比对，避免通过响应耗时枚举邮箱。 */
    private final String dummyHash;

    public AccountService(AccountMapper accountMapper,
                          InvitationMapper invitationMapper,
                          AuthTokenMapper authTokenMapper,
                          EmailProtector emailProtector,
                          PasswordEncoder passwordEncoder,
                          AccountMailSender mailSender,
                          Clock clock) {
        this.accountMapper = accountMapper;
        this.invitationMapper = invitationMapper;
        this.authTokenMapper = authTokenMapper;
        this.emailProtector = emailProtector;
        this.passwordEncoder = passwordEncoder;
        this.mailSender = mailSender;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(OpaqueTokens.generate());
    }

    /** US-001：凭有效邀请码开通账号，账号初始为未验证，并发送验证邮件。 */
    @Transactional
    public AccountView register(String invitationCode, String email, String password) {
        requireAcceptablePassword("password", password);
        LocalDateTime now = now();

        Invitation invitation = invitationMapper.findByTokenDigest(OpaqueTokens.digest(invitationCode));
        if (invitation == null || !invitation.isUsableAt(now)) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIAL);
        }

        String emailIndex = emailProtector.index(email);
        if (accountMapper.findByEmailIndex(emailIndex) != null) {
            throw new ApiException(ErrorCode.OPERATION_NOT_ALLOWED, "该邮箱不能用于开通账号");
        }

        Account account = new Account();
        account.setEmailCiphertext(emailProtector.encrypt(email));
        account.setEmailIndex(emailIndex);
        account.setPasswordHash(passwordEncoder.encode(password));
        account.setStatus(AccountStatus.UNVERIFIED);
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        try {
            accountMapper.insert(account);
        } catch (DuplicateKeyException ex) {
            // 并发开通同一邮箱：唯一约束兜底
            throw new ApiException(ErrorCode.OPERATION_NOT_ALLOWED, "该邮箱不能用于开通账号");
        }

        // 条件更新：并发使用同一邀请码时只有一个请求成功，失败方整体回滚
        if (invitationMapper.markUsed(invitation.getId(), account.getId(), now) != 1) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIAL);
        }

        String token = issueToken(account.getId(), TokenPurpose.EMAIL_VERIFICATION, now);
        mailSender.sendEmailVerification(EmailProtector.normalize(email), token);
        return toView(account);
    }

    /** 已登录的未验证账号重新请求验证邮件；旧的验证令牌随即作废。 */
    @Transactional
    public void resendEmailVerification(long accountId) {
        Account account = requireAccount(accountId);
        if (account.getStatus() != AccountStatus.UNVERIFIED) {
            throw new ApiException(ErrorCode.OPERATION_NOT_ALLOWED, "邮箱已验证或账号状态不允许此操作");
        }
        LocalDateTime now = now();
        authTokenMapper.invalidateOutstanding(accountId, TokenPurpose.EMAIL_VERIFICATION, now);
        String token = issueToken(accountId, TokenPurpose.EMAIL_VERIFICATION, now);
        mailSender.sendEmailVerification(emailProtector.decrypt(account.getEmailCiphertext()), token);
    }

    /** 消耗邮箱验证令牌，账号由未验证变为有效。 */
    @Transactional
    public void confirmEmailVerification(String token) {
        LocalDateTime now = now();
        AuthToken authToken = consumeToken(token, TokenPurpose.EMAIL_VERIFICATION, now);
        if (accountMapper.markEmailVerified(authToken.getAccountId(), now,
                AccountStatus.UNVERIFIED, AccountStatus.ACTIVE) != 1) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIAL);
        }
    }

    /**
     * US-002 登录。不使用事务：失败计数必须在抛出业务错误后仍然保留。
     */
    public AccountPrincipal authenticate(String email, String password) {
        LocalDateTime now = now();
        Account account = accountMapper.findByEmailIndex(emailProtector.index(email));
        if (account == null) {
            passwordEncoder.matches(password, dummyHash);
            throw new ApiException(ErrorCode.BAD_CREDENTIALS);
        }
        if (account.isLockedAt(now)) {
            throw new ApiException(ErrorCode.TOO_MANY_REQUESTS);
        }
        if (!passwordEncoder.matches(password, account.getPasswordHash())) {
            recordLoginFailure(account, now);
        }
        if (account.getStatus() != AccountStatus.ACTIVE && account.getStatus() != AccountStatus.UNVERIFIED) {
            // 冻结、注销中、已注销账号不能登录；与密码错误同一说明
            throw new ApiException(ErrorCode.BAD_CREDENTIALS);
        }
        accountMapper.resetLoginFailures(account.getId(), now);
        return new AccountPrincipal(account.getId());
    }

    /**
     * 请求密码恢复。无论邮箱是否存在、是否已验证，调用方都返回同样的 202，
     * 只有已验证的有效账号才真正发信（US-002：恢复只发送到已验证邮箱）。
     */
    @Transactional
    public void requestPasswordReset(String email) {
        Account account = accountMapper.findByEmailIndex(emailProtector.index(email));
        if (account == null || account.getEmailVerifiedAt() == null || account.getStatus() != AccountStatus.ACTIVE) {
            return;
        }
        LocalDateTime now = now();
        authTokenMapper.invalidateOutstanding(account.getId(), TokenPurpose.PASSWORD_RESET, now);
        String token = issueToken(account.getId(), TokenPurpose.PASSWORD_RESET, now);
        mailSender.sendPasswordReset(emailProtector.decrypt(account.getEmailCiphertext()), token);
    }

    /** 消耗恢复令牌并设置新密码；同时解除登录锁定、作废其余未用恢复令牌。 */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        requireAcceptablePassword("newPassword", newPassword);
        LocalDateTime now = now();
        AuthToken authToken = consumeToken(token, TokenPurpose.PASSWORD_RESET, now);
        accountMapper.updatePasswordHash(authToken.getAccountId(), passwordEncoder.encode(newPassword), now);
        authTokenMapper.invalidateOutstanding(authToken.getAccountId(), TokenPurpose.PASSWORD_RESET, now);
    }

    public AccountView getAccount(long accountId) {
        return toView(requireAccount(accountId));
    }

    private void recordLoginFailure(Account account, LocalDateTime now) {
        // 上一次锁定已过期：重新计数
        int previous = account.getLockedUntil() != null ? 0 : account.getFailedLoginCount();
        int count = previous + 1;
        LocalDateTime lockedUntil = count >= AuthPolicy.MAX_FAILED_LOGINS
                ? now.plus(AuthPolicy.LOGIN_LOCK_DURATION) : null;
        accountMapper.updateLoginFailure(account.getId(), count, lockedUntil, now);
        throw new ApiException(lockedUntil != null ? ErrorCode.TOO_MANY_REQUESTS : ErrorCode.BAD_CREDENTIALS);
    }

    private AuthToken consumeToken(String token, TokenPurpose purpose, LocalDateTime now) {
        AuthToken authToken = authTokenMapper.findByTokenDigest(OpaqueTokens.digest(token));
        if (authToken == null || !authToken.isConsumableAt(purpose, now)
                || authTokenMapper.consume(authToken.getId(), now) != 1) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIAL);
        }
        return authToken;
    }

    private String issueToken(long accountId, TokenPurpose purpose, LocalDateTime now) {
        String raw = OpaqueTokens.generate();
        AuthToken token = new AuthToken();
        token.setAccountId(accountId);
        token.setPurpose(purpose);
        token.setTokenDigest(OpaqueTokens.digest(raw));
        token.setExpiresAt(now.plus(purpose.ttl()));
        token.setCreatedAt(now);
        token.setUpdatedAt(now);
        authTokenMapper.insert(token);
        return raw;
    }

    private Account requireAccount(long accountId) {
        Account account = accountMapper.findById(accountId);
        if (account == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        return account;
    }

    private AccountView toView(Account account) {
        return new AccountView(account.getId(), emailProtector.decrypt(account.getEmailCiphertext()),
                account.getEmailVerifiedAt() != null, account.getStatus());
    }

    /** DEC-017：至少 12 个字符、不设复杂度规则；BCrypt 只处理前 72 字节，超出则拒绝而非静默截断。 */
    static void requireAcceptablePassword(String field, String password) {
        if (password == null || password.codePointCount(0, password.length()) < AuthPolicy.PASSWORD_MIN_LENGTH) {
            throw validation(field, "TooShort", "密码至少 " + AuthPolicy.PASSWORD_MIN_LENGTH + " 个字符");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > AuthPolicy.PASSWORD_MAX_BYTES) {
            throw validation(field, "TooLong", "密码过长");
        }
    }

    private static ApiException validation(String field, String reason, String message) {
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        return new ApiException(code, code.defaultMessage(), List.of(new FieldErrorItem(field, reason, message)));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
