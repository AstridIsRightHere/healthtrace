package com.healthtrace.account.api;

import com.healthtrace.account.api.AccountRequests.TokenRequest;
import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.account.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 邮箱验证（FR-001）。令牌消耗使用 POST，不用 GET 改变状态（docs/08）。 */
@RestController
@RequestMapping("/api/v1/email-verifications")
public class EmailVerificationController {

    private final AccountService accountService;

    public EmailVerificationController(AccountService accountService) {
        this.accountService = accountService;
    }

    /** 已登录的未验证账号重新请求验证邮件。 */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resend(@AuthenticationPrincipal AccountPrincipal principal) {
        accountService.resendEmailVerification(principal.accountId());
    }

    @PostMapping("/confirmations")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@Valid @RequestBody TokenRequest request) {
        accountService.confirmEmailVerification(request.token());
    }
}
