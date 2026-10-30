package com.healthtrace.report.api;

import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.report.service.ReportRevisionService;
import com.healthtrace.report.service.RevisionViews.CreateRevisionCommand;
import com.healthtrace.report.service.RevisionViews.ObservationVersionView;
import com.healthtrace.report.service.RevisionViews.ReviseResult;
import com.healthtrace.report.service.RevisionViews.RevisionView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * 已确认报告的指标级修订与修改记录（FR-014）。需登录，写操作需 CSRF；无权访问与不存在统一 404。
 * <p>
 * 修订不覆盖原图、原始 OCR，也不物理改写/删除正式指标行：修改将旧行标记 SUPERSEDED 并新增替代行，
 * 删除将旧行标记 RETRACTED。
 */
@RestController
public class ReportRevisionController {

    private final ReportRevisionService revisionService;

    public ReportRevisionController(ReportRevisionService revisionService) {
        this.revisionService = revisionService;
    }

    /** 提交一次修订（新增/修改/删除指标，可同时修改检查日期），成功返回 201。 */
    @PostMapping("/api/v1/reports/{reportId}/revisions")
    public ResponseEntity<ReviseResult> create(@AuthenticationPrincipal AccountPrincipal principal,
                                               @PathVariable long reportId,
                                               @RequestBody(required = false) CreateRevisionCommand command) {
        ReviseResult result = revisionService.create(principal.accountId(), reportId, command);
        URI location = URI.create("/api/v1/reports/" + reportId + "/revisions/" + result.revisionId());
        return ResponseEntity.created(location).body(result);
    }

    /** 修改记录，按时间倒序。 */
    @GetMapping("/api/v1/reports/{reportId}/revisions")
    public List<RevisionView> revisions(@AuthenticationPrincipal AccountPrincipal principal,
                                        @PathVariable long reportId) {
        return revisionService.listRevisions(principal.accountId(), reportId);
    }

    /** 单个指标的版本链（经 supersedes_id 串联），最新在前。 */
    @GetMapping("/api/v1/reports/{reportId}/observations/{observationId}/history")
    public List<ObservationVersionView> observationHistory(@AuthenticationPrincipal AccountPrincipal principal,
                                                           @PathVariable long reportId,
                                                           @PathVariable long observationId) {
        return revisionService.observationHistory(principal.accountId(), reportId, observationId);
    }
}
