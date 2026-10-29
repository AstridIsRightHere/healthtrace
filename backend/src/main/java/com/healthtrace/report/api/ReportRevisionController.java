package com.healthtrace.report.api;

import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.report.service.ReportRevisionService;
import com.healthtrace.report.service.RevisionViews.ReviseCommand;
import com.healthtrace.report.service.RevisionViews.ReviseResult;
import com.healthtrace.report.service.RevisionViews.RevisionView;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 修改已确认报告与修改记录（FR-014）。需登录，写操作需 CSRF；无权访问与不存在统一 404。
 */
@RestController
public class ReportRevisionController {

    private final ReportRevisionService revisionService;

    public ReportRevisionController(ReportRevisionService revisionService) {
        this.revisionService = revisionService;
    }

    @PatchMapping("/api/v1/reports/{reportId}")
    public ReviseResult revise(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable long reportId,
                               @RequestBody ReviseCommand command) {
        return revisionService.revise(principal.accountId(), reportId, command);
    }

    @GetMapping("/api/v1/reports/{reportId}/revisions")
    public List<RevisionView> revisions(@AuthenticationPrincipal AccountPrincipal principal,
                                        @PathVariable long reportId) {
        return revisionService.listRevisions(principal.accountId(), reportId);
    }
}
