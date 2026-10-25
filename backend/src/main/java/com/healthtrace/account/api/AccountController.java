package com.healthtrace.account.api;

import com.healthtrace.account.api.AccountRequests.RegisterRequest;
import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.account.service.AccountService;
import com.healthtrace.account.service.AccountView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 邀请开通与账号状态（FR-001，US-001）。 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountView register(@Valid @RequestBody RegisterRequest request) {
        return accountService.register(request.invitationCode(), request.email(), request.password());
    }

    @GetMapping("/me")
    public AccountView me(@AuthenticationPrincipal AccountPrincipal principal) {
        return accountService.getAccount(principal.accountId());
    }
}
