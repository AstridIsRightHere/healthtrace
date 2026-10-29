package com.healthtrace.report.api;

import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.report.service.ReportListRemovalService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 报告列表移除（回收站）与恢复（FR-016 / DEC-002）。需登录 + CSRF；无权访问、不存在与已删除统一 404 HT-3001。
 * 回收站列表见 GET /api/v1/patient-profiles/{profileId}/reports?status=REMOVED，彻底删除见 DELETE /api/v1/reports/{reportId}。
 */
@RestController
public class ReportListRemovalController {

    private final ReportListRemovalService listRemovalService;

    public ReportListRemovalController(ReportListRemovalService listRemovalService) {
        this.listRemovalService = listRemovalService;
    }

    /** 移出列表与图表：204；已在回收站时幂等 204。 */
    @PostMapping("/api/v1/reports/{reportId}/list-removals")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFromList(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable long reportId) {
        listRemovalService.remove(principal.accountId(), reportId);
    }

    /** 从回收站恢复：204；报告不在回收站时 409 HT-4002。 */
    @DeleteMapping("/api/v1/reports/{reportId}/list-removals")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restoreToList(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable long reportId) {
        listRemovalService.restore(principal.accountId(), reportId);
    }
}
