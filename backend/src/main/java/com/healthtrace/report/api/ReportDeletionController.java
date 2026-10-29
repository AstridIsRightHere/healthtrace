package com.healthtrace.report.api;

import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.report.service.ReportDeletionService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 彻底删除本人名下的报告（FR-016，任意处理状态，含回收站中的报告）。需登录 + CSRF；
 * 无权访问、不存在与已删除统一 404 HT-3001。
 */
@RestController
public class ReportDeletionController {

    private final ReportDeletionService deletionService;

    public ReportDeletionController(ReportDeletionService deletionService) {
        this.deletionService = deletionService;
    }

    @DeleteMapping("/api/v1/reports/{reportId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteReport(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable long reportId) {
        deletionService.delete(principal.accountId(), reportId);
    }
}
