package com.healthtrace.account.api;

import com.healthtrace.account.api.AccountRequests.LoginRequest;
import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.account.service.AccountService;
import com.healthtrace.account.service.AccountView;
import com.healthtrace.common.security.SessionAbsoluteTimeoutFilter;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.rights.audit.AuditAction;
import com.healthtrace.rights.audit.AuditOutcome;
import com.healthtrace.rights.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;

/** 登录与退出（FR-002，US-002）。 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final AccountService accountService;
    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final AuditService auditService;
    private final Clock clock;

    public SessionController(AccountService accountService,
                             SecurityContextRepository securityContextRepository,
                             CsrfTokenRepository csrfTokenRepository,
                             AuditService auditService,
                             Clock clock) {
        this.auditService = auditService;
        this.accountService = accountService;
        this.securityContextRepository = securityContextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
        this.clock = clock;
    }

    @PostMapping
    public AccountView login(@Valid @RequestBody LoginRequest request,
                             HttpServletRequest httpRequest,
                             HttpServletResponse httpResponse) {
        AccountPrincipal principal;
        try {
            principal = accountService.authenticate(request.email(), request.password());
        } catch (ApiException e) {
            // 失败登录只记录结果，不记录邮箱（避免在审计中留存个人信息）
            auditService.record(null, AuditAction.ACCOUNT_LOGIN, null, AuditOutcome.DENIED);
            throw e;
        }
        auditService.success(principal.accountId(), AuditAction.ACCOUNT_LOGIN,
                AuditService.token("account", principal.accountId()));

        // 防会话固定：登录成功后轮换会话 ID，并作废登录前的 CSRF 令牌（前端需重新获取）
        httpRequest.getSession(true);
        httpRequest.changeSessionId();
        csrfTokenRepository.saveToken(null, httpRequest, httpResponse);
        httpRequest.getSession().setAttribute(SessionAbsoluteTimeoutFilter.LOGIN_AT_ATTRIBUTE, clock.instant());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        return accountService.getAccount(principal.accountId());
    }

    @DeleteMapping("/current")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest httpRequest) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AccountPrincipal p) {
            auditService.success(p.accountId(), AuditAction.ACCOUNT_LOGOUT, AuditService.token("account", p.accountId()));
        }
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }
}
