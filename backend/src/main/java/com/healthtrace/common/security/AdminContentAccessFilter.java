package com.healthtrace.common.security;

import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.account.domain.AccountRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * FR-018：管理员不得访问患者健康数据。
 * 所有患者数据路径（报告、档案、图片、OCR、趋势、就诊）对 ADMIN 角色返回 403。
 */
@Component
public class AdminContentAccessFilter extends OncePerRequestFilter {

    private static final String[] PATIENT_DATA_PATTERNS = {
            "/api/v1/patient-profiles",
            "/api/v1/reports",
            "/api/v1/photos",
            "/api/v1/trends",
            "/api/v1/visits",
            "/api/v1/followed-metrics",
            "/api/v1/temporary-metrics",
            "/api/v1/metric-summaries"
    };

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        
        if (auth != null && auth.getPrincipal() instanceof AccountPrincipal principal) {
            if (principal.role() == AccountRole.ADMIN && isPatientDataPath(request.getRequestURI())) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":\"HT-1003\",\"message\":\"管理员不可访问患者健康数据\"}");
                return;
            }
        }
        
        filterChain.doFilter(request, response);
    }

    private boolean isPatientDataPath(String uri) {
        for (String pattern : PATIENT_DATA_PATTERNS) {
            if (uri.startsWith(pattern)) {
                return true;
            }
        }
        return false;
    }
}
