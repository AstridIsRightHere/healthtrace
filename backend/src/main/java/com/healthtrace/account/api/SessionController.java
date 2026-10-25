package com.healthtrace.account.api;

import com.healthtrace.account.api.AccountRequests.LoginRequest;
import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.account.service.AccountService;
import com.healthtrace.account.service.AccountView;
import com.healthtrace.common.security.SessionAbsoluteTimeoutFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
    private final Clock clock;

    public SessionController(AccountService accountService,
                             SecurityContextRepository securityContextRepository,
                             CsrfTokenRepository csrfTokenRepository,
                             Clock clock) {
        this.accountService = accountService;
        this.securityContextRepository = securityContextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
        this.clock = clock;
    }

    @PostMapping
    public AccountView login(@Valid @RequestBody LoginRequest request,
                             HttpServletRequest httpRequest,
                             HttpServletResponse httpResponse) {
        AccountPrincipal principal = accountService.authenticate(request.email(), request.password());

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
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }
}
