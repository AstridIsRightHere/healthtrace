package com.healthtrace.account.mapper;

import com.healthtrace.account.domain.Account;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 账号注销（FR-017）专用的锁定、查询与物理删除。SQL 见 resources/mapper/account/AccountClosureMapper.xml。
 * 只供 AccountClosureService 在单事务内调用；方法本身不做权限判断。
 */
@Mapper
public interface AccountClosureMapper {

    /** 锁定账号行（SELECT ... FOR UPDATE），并发注销/登录失败计数在此串行化；不存在返回 null。 */
    Account lockAccount(@Param("accountId") long accountId);

    /** 该账号持有任意授权记录（不限状态）的档案 ID。 */
    List<Long> findAccessibleProfileIds(@Param("accountId") long accountId);

    /** 除本账号外仍有授权记录（不限状态）的账号数；为 0 表示档案只归属本账号，可整体处置。 */
    int countOtherAccessHolders(@Param("profileId") long profileId, @Param("accountId") long accountId);

    /** 档案下全部报告 ID（含草稿、识别中、列表移除等所有状态）。 */
    List<Long> findReportIdsByProfile(@Param("profileId") long profileId);

    /** 删除就诊；visit_report 关联行由外键 ON DELETE CASCADE 一并删除。 */
    int deleteVisitsByProfile(@Param("profileId") long profileId);

    int deleteFollowedMetricsByProfile(@Param("profileId") long profileId);

    int deleteConsentsByProfile(@Param("profileId") long profileId);

    int deleteAccessByProfile(@Param("profileId") long profileId);

    int deleteProfile(@Param("profileId") long profileId);

    /** 共享档案上只撤掉本账号自己的授权记录与同意记录，档案数据保留给其他授权方。 */
    int deleteAccessByAccount(@Param("accountId") long accountId);

    int deleteConsentsByAccount(@Param("accountId") long accountId);

    /** 档案处置后仍以外键引用本账号的行数（报告/OCR/就诊/修改记录的创建人、确认人）；>0 时不能删除账号行。 */
    int countRemainingReferences(@Param("accountId") long accountId);

    int deleteAuthTokens(@Param("accountId") long accountId);

    /** 删除本人签发的邀请（无其他表引用 invitation）。 */
    int deleteIssuedInvitations(@Param("accountId") long accountId);

    /** 本人开通时用过的他人邀请：保留“已使用”状态，仅断开 used_by。 */
    int clearInvitationUsage(@Param("accountId") long accountId);

    int deleteAccount(@Param("accountId") long accountId);
}
