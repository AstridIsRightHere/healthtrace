package com.healthtrace.common.security;

import com.healthtrace.account.domain.AuthPolicy;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.ErrorResponseWriter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import java.time.Clock;

/**
 * 同源服务端会话 + CSRF（ADR-007，DEC-017）。
 * <p>白名单逐条列出“方法 + 路径”，不使用通配放行；未列出的请求一律要求登录，兜底为 denyAll 之外的 authenticated。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecretKeyProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   CsrfTokenRepository csrfTokenRepository,
                                                   SecurityContextRepository securityContextRepository,
                                                   ErrorResponseWriter errorWriter,
                                                   Clock clock) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // 匿名可访问的认证入口（docs/08，FR-001/FR-002）
                        .requestMatchers(HttpMethod.GET, "/api/v1/csrf-token").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/accounts").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/email-verifications/confirmations").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/sessions").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/password-reset-requests").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/password-resets").permitAll()
                        // OpenAPI 文档：仅 dev 启用（application-dev.yml），其余 profile 关闭后这些路径为 404
                        .requestMatchers(HttpMethod.GET, "/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        // 其余接口（含 GET /api/v1/accounts/me、POST /api/v1/email-verifications 重发验证邮件）需登录
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        // 前端从 /api/v1/csrf-token 取得原值并放入 X-CSRF-TOKEN 请求头
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .securityContext(ctx -> ctx.securityContextRepository(securityContextRepository))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        // 登录接口里手动轮换会话 ID，这里关闭默认策略避免重复处理
                        .sessionFixation(fixation -> fixation.none()))
                .addFilterBefore(new SessionAbsoluteTimeoutFilter(clock), SecurityContextHolderFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> errorWriter.write(req, res, ErrorCode.UNAUTHENTICATED))
                        .accessDeniedHandler((req, res, e) -> errorWriter.write(req, res,
                                e instanceof CsrfException ? ErrorCode.CSRF_FAILED : ErrorCode.ACCESS_DENIED)))
                .headers(headers -> headers.frameOptions(frame -> frame.deny()))
                // 登录与退出由 SessionController 实现 JSON 接口，关闭表单/Basic/默认退出页
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .anonymous(anonymous -> {
                });
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(AuthPolicy.BCRYPT_COST);
    }

    @Bean
    public CsrfTokenRepository csrfTokenRepository(Clock clock) {
        return new ExpiringCsrfTokenRepository(clock);
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * 认证由 AccountService 自行完成，不使用 UserDetailsService。
     * 显式提供空实现，阻止 Boot 自动生成带随机密码的内存用户并在日志中打印密码。
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            throw new org.springframework.security.core.userdetails.UsernameNotFoundException("不支持");
        };
    }
}
