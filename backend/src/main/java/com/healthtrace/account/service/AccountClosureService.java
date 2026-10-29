package com.healthtrace.account.service;

import com.healthtrace.account.domain.Account;
import com.healthtrace.account.mapper.AccountClosureMapper;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.report.service.ReportPurger;
import com.healthtrace.rights.audit.AuditAction;
import com.healthtrace.rights.audit.AuditService;
import com.healthtrace.rights.deletion.DeletionEventType;
import com.healthtrace.rights.deletion.DeletionLedgerService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 账号注销（FR-017）：校验当前密码后，在单事务内物理删除本账号及其独占档案的全部数据。
 * <ul>
 *   <li>先锁账号行，同一账号的并发注销在此串行化；第二个请求看到账号已不存在，按未登录处理。</li>
 *   <li>报告逐份交给 ReportPurger（删除修改记录、就诊关联、结果、OCR 任务、图片并登记对象存储清理）。</li>
 *   <li>共享档案（还有其他账号持有授权）只撤销本账号的授权与同意，数据保留；若本账号仍是其中记录的
 *       创建人/确认人，外键不允许删除账号行，整体拒绝（MVP 无共享流程，正常不会出现）。</li>
 *   <li>账号行最后删除，同一邮箱随后可重新开通；处置台账与审计写在同一事务，失败一并回滚。</li>
 * </ul>
 * 密码错误抛 BAD_CREDENTIALS，由调用方在事务外记录 DENIED 审计（事务回滚不能带走失败记录）。
 */
@Service
public class AccountClosureService {

    private final AccountClosureMapper closureMapper;
    private final ReportPurger reportPurger;
    private final DeletionLedgerService ledgerService;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;

    public AccountClosureService(AccountClosureMapper closureMapper,
                                 ReportPurger reportPurger,
                                 DeletionLedgerService ledgerService,
                                 AuditService auditService,
                                 PasswordEncoder passwordEncoder) {
        this.closureMapper = closureMapper;
        this.reportPurger = reportPurger;
        this.ledgerService = ledgerService;
        this.auditService = auditService;
        this.passwordEncoder = passwordEncoder;
    }

    /** 返回本次删除的数据库行数（含各报告处置行数），写入处置台账 item_count。 */
    @Transactional
    public int close(long accountId, String password) {
        Account account = closureMapper.lockAccount(accountId);
        if (account == null) {
            // 会话仍在但账号已被注销（并发请求）：按会话失效处理
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        if (password == null || !passwordEncoder.matches(password, account.getPasswordHash())) {
            throw new ApiException(ErrorCode.BAD_CREDENTIALS);
        }

        int removed = 0;
        for (Long profileId : closureMapper.findAccessibleProfileIds(accountId)) {
            if (closureMapper.countOtherAccessHolders(profileId, accountId) > 0) {
                continue; // 共享档案：下面只撤销本账号的授权与同意
            }
            removed += purgeProfile(profileId);
        }
        // 先检查再删同意：共享档案上的 OCR 任务可能引用本账号的同意记录，直接删会触发外键错误
        if (closureMapper.countRemainingReferences(accountId) > 0) {
            throw new ApiException(ErrorCode.OPERATION_NOT_ALLOWED);
        }
        removed += closureMapper.deleteAccessByAccount(accountId);
        removed += closureMapper.deleteConsentsByAccount(accountId);
        removed += closureMapper.deleteAuthTokens(accountId);
        removed += closureMapper.deleteIssuedInvitations(accountId);
        closureMapper.clearInvitationUsage(accountId);
        removed += closureMapper.deleteAccount(accountId);

        ledgerService.record(DeletionEventType.ACCOUNT_CLOSURE, accountId, removed);
        auditService.success(accountId, AuditAction.ACCOUNT_CLOSE, AuditService.token("account", accountId));
        return removed;
    }

    /** 独占档案整体删除：报告 → 就诊（关联级联）→ 关注 → 同意 → 授权 → 档案。 */
    private int purgeProfile(long profileId) {
        int removed = 0;
        List<Long> reportIds = closureMapper.findReportIdsByProfile(profileId);
        for (Long reportId : reportIds) {
            removed += reportPurger.purge(reportId);
        }
        removed += closureMapper.deleteVisitsByProfile(profileId);
        removed += closureMapper.deleteFollowedMetricsByProfile(profileId);
        removed += closureMapper.deleteConsentsByProfile(profileId);
        removed += closureMapper.deleteAccessByProfile(profileId);
        removed += closureMapper.deleteProfile(profileId);
        return removed;
    }
}
