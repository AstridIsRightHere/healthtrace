package com.healthtrace.account.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 账号相关请求体。密码长度规则由 AccountService 统一校验（DEC-017），这里只做非空与格式校验。
 * 记录类的 toString 会包含字段值，禁止把这些对象写入日志。
 */
public final class AccountRequests {

    private AccountRequests() {
    }

    public record RegisterRequest(
            @NotBlank @Size(max = 128) String invitationCode,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank String password) {
    }

    public record LoginRequest(
            @NotBlank @Size(max = 254) String email,
            @NotBlank @Size(max = 1024) String password) {
    }

    public record TokenRequest(@NotBlank @Size(max = 128) String token) {
    }

    public record PasswordResetRequest(@NotBlank @Email @Size(max = 254) String email) {
    }

    /** 账号注销（FR-017）：需再次输入当前密码。 */
    public record AccountClosureRequest(@NotBlank @Size(max = 1024) String password) {
    }

    public record PasswordResetConfirmRequest(
            @NotBlank @Size(max = 128) String token,
            @NotBlank String newPassword) {
    }
}
