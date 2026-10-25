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
 * 彻底删除本人名下尚未确认的报告。需登录 + CSRF；无权访问与不存在统一 404 HT-3001。
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
        deletionService.deleteUnconfirmed(principal.accountId(), reportId);
    }
}
