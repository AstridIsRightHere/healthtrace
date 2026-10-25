package com.healthtrace.common.security;

import com.healthtrace.account.domain.AuthPolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import java.io.Serializable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 会话内保存的 CSRF 令牌，附带签发时间；超过 DEC-017 规定的 2 小时后视为不存在，
 * 使用过期令牌的状态变更请求会被 CsrfFilter 拒绝（HT-2005）。
 * Spring Security 自带的 HttpSessionCsrfTokenRepository 没有有效期，因此自行实现。
 */
public class ExpiringCsrfTokenRepository implements CsrfTokenRepository {

    public static final String HEADER_NAME = "X-CSRF-TOKEN";
    public static final String PARAMETER_NAME = "_csrf";
    static final String SESSION_ATTRIBUTE = ExpiringCsrfTokenRepository.class.getName() + ".TOKEN";

    private final Clock clock;
    private final Duration ttl;

    public ExpiringCsrfTokenRepository(Clock clock) {
        this(clock, AuthPolicy.CSRF_TOKEN_TTL);
    }

    ExpiringCsrfTokenRepository(Clock clock, Duration ttl) {
        this.clock = clock;
        this.ttl = ttl;
    }

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        return new DefaultCsrfToken(HEADER_NAME, PARAMETER_NAME, UUID.randomUUID().toString());
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        if (token == null) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.removeAttribute(SESSION_ATTRIBUTE);
            }
            return;
        }
        request.getSession().setAttribute(SESSION_ATTRIBUTE, new StoredToken(token.getToken(), clock.instant()));
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        if (!(session.getAttribute(SESSION_ATTRIBUTE) instanceof StoredToken stored)) {
            return null;
        }
        if (!clock.instant().isBefore(stored.issuedAt().plus(ttl))) {
            session.removeAttribute(SESSION_ATTRIBUTE);
            return null;
        }
        return new DefaultCsrfToken(HEADER_NAME, PARAMETER_NAME, stored.value());
    }

    record StoredToken(String value, Instant issuedAt) implements Serializable {
    }
}
