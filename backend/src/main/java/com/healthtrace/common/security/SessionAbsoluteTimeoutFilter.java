package com.healthtrace.common.security;

import com.healthtrace.account.domain.AuthPolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;

/**
 * 会话绝对超时（DEC-017：8 小时，活动不能延长）。
 * 不活跃超时 30 分钟由 servlet 容器的 session timeout 负责；这里检查登录时刻，超过上限即作废会话，
 * 后续过滤器看到的就是未登录请求。
 */
public class SessionAbsoluteTimeoutFilter extends OncePerRequestFilter {

    /** 登录成功时写入的会话属性，值为登录时刻 {@link Instant}。 */
    public static final String LOGIN_AT_ATTRIBUTE = SessionAbsoluteTimeoutFilter.class.getName() + ".LOGIN_AT";

    private final Clock clock;

    public SessionAbsoluteTimeoutFilter(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(LOGIN_AT_ATTRIBUTE) instanceof Instant loginAt
                && !clock.instant().isBefore(loginAt.plus(AuthPolicy.SESSION_ABSOLUTE_TIMEOUT))) {
            session.invalidate();
        }
        chain.doFilter(request, response);
    }
}
