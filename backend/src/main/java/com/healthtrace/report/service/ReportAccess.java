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

    public Report requireOwnedReport(long accountId, long reportId) {
        Report report = reportMapper.findById(reportId);
        if (report == null || report.getVisibilityStatus() != VisibilityStatus.NORMAL
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
