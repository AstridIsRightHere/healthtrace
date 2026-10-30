package com.healthtrace.account.api;

import com.healthtrace.account.api.AccountRequests.AccountClosureRequest;
import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.account.service.AccountClosureService;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.rights.audit.AuditAction;
import com.healthtrace.rights.audit.AuditOutcome;
import com.healthtrace.rights.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 账号注销（FR-017）：需登录 + CSRF + 当前密码；成功后会话立即失效。 */
@RestController
@RequestMapping("/api/v1/account-closure")
public class AccountClosureController {

    private final AccountClosureService closureService;
    private final AuditService auditService;

    public AccountClosureController(AccountClosureService closureService, AuditService auditService) {
        this.closureService = closureService;
        this.auditService = auditService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(@AuthenticationPrincipal AccountPrincipal principal,
                      @Valid @RequestBody AccountClosureRequest request,
                      HttpServletRequest httpRequest) {
        long accountId = principal.accountId();
        try {
            closureService.close(accountId, request.password());
        } catch (ApiException e) {
            if (e.getErrorCode() == ErrorCode.BAD_CREDENTIALS) {
                // 注销事务已回滚；拒绝记录在事务外单独写入
                auditService.record(accountId, AuditAction.ACCOUNT_CLOSE,
                        AuditService.token("account", accountId), AuditOutcome.DENIED);
            }
            throw e;
        }
        // 事务已提交：账号不复存在，当前会话立即作废
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }
}
