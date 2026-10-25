package com.healthtrace.account.api;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前端在发起状态变更请求前取得 CSRF 令牌（ADR-007）。令牌有效期 2 小时（DEC-017），
 * 登录后旧令牌作废，需重新获取。
 */
@RestController
@RequestMapping("/api/v1/csrf-token")
public class CsrfController {

    public record CsrfTokenResponse(String headerName, String token) {
    }

    @GetMapping
    public CsrfTokenResponse csrfToken(CsrfToken csrfToken) {
        // getToken() 触发延迟加载：会话内无有效令牌时生成并保存新令牌
        return new CsrfTokenResponse(csrfToken.getHeaderName(), csrfToken.getToken());
    }
}
