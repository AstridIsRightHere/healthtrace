package com.healthtrace.account.api;

import com.healthtrace.account.api.AccountRequests.PasswordResetConfirmRequest;
import com.healthtrace.account.api.AccountRequests.PasswordResetRequest;
import com.healthtrace.account.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 密码找回（FR-002，US-002）。 */
@RestController
public class PasswordResetController {

    private final AccountService accountService;

    public PasswordResetController(AccountService accountService) {
        this.accountService = accountService;
    }

    /** 无论账号是否存在都返回 202，不透露邮箱是否注册（docs/08）。 */
    @PostMapping("/api/v1/password-reset-requests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void request(@Valid @RequestBody PasswordResetRequest request) {
        accountService.requestPasswordReset(request.email());
    }

    @PostMapping("/api/v1/password-resets")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        accountService.resetPassword(request.token(), request.newPassword());
    }
}
