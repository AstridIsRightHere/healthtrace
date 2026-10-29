package com.healthtrace.report.service;

import com.healthtrace.account.domain.Account;
import com.healthtrace.account.mapper.AccountMapper;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.profile.mapper.ProfileMapper;
import com.healthtrace.report.domain.Report;
import com.healthtrace.report.domain.VisibilityStatus;
import com.healthtrace.report.mapper.ReportMapper;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * 报告访问控制：reportId 先解析到 profileId，再要求当前账号持有该档案的 ACTIVE 授权。
 * 不存在与无权访问统一 404 HT-3001，不泄露其他档案的资源是否存在。
 */
@Component
public class ReportAccess {

    private final ReportMapper reportMapper;
    private final ProfileMapper profileMapper;
    private final AccountMapper accountMapper;

    public ReportAccess(ReportMapper reportMapper, ProfileMapper profileMapper, AccountMapper accountMapper) {
        this.reportMapper = reportMapper;
        this.profileMapper = profileMapper;
        this.accountMapper = accountMapper;
    }

    /** 常规查看/编辑入口：仅 NORMAL 报告可见，列表移除（回收站）中的报告按不存在处理。 */
    public Report requireOwnedReport(long accountId, long reportId) {
        return requireOwned(accountId, reportId, EnumSet.of(VisibilityStatus.NORMAL));
    }

    /**
     * 回收站与彻底删除入口（FR-016）：NORMAL 或 LIST_REMOVED 均可；DELETING/DELETED 视为已删除，统一 404。
     */
    public Report requireOwnedReportAnyVisibility(long accountId, long reportId) {
        return requireOwned(accountId, reportId, EnumSet.of(VisibilityStatus.NORMAL, VisibilityStatus.LIST_REMOVED));
    }

    private Report requireOwned(long accountId, long reportId, Set<VisibilityStatus> allowed) {
        Report report = reportMapper.findById(reportId);
        if (report == null || !allowed.contains(report.getVisibilityStatus())
                || profileMapper.findActiveByAccountAndProfile(accountId, report.getProfileId()) == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return report;
    }

    /** US-001：未验证邮箱的账号不能上传或把资料发送给第三方识别。 */
    public void requireVerifiedEmail(long accountId) {
        Account account = accountMapper.findById(accountId);
        if (account == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        if (account.getEmailVerifiedAt() == null) {
            throw new ApiException(ErrorCode.EMAIL_NOT_VERIFIED);
        }
    }
}
