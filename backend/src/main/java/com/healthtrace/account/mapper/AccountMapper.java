package com.healthtrace.account.mapper;

import com.healthtrace.account.domain.Account;
import com.healthtrace.account.domain.AccountStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * ACCOUNT 表访问。SQL 见 resources/mapper/account/AccountMapper.xml。
 */
@Mapper
public interface AccountMapper {

    /** 插入账号并回填自增 ID。 */
    int insert(Account account);

    Account findById(@Param("id") long id);

    /** 按规范化邮箱的 HMAC 检索，不使用明文邮箱。 */
    Account findByEmailIndex(@Param("emailIndex") String emailIndex);

    /** 仅当账号仍为未验证时标记已验证，返回受影响行数。 */
    int markEmailVerified(@Param("id") long id,
                          @Param("verifiedAt") LocalDateTime verifiedAt,
                          @Param("fromStatus") AccountStatus fromStatus,
                          @Param("toStatus") AccountStatus toStatus);

    /** 更新密码哈希，同时清零失败计数并解除锁定。 */
    int updatePasswordHash(@Param("id") long id,
                           @Param("passwordHash") String passwordHash,
                           @Param("updatedAt") LocalDateTime updatedAt);

    /** 记录一次登录失败后的计数与锁定截止时间（未锁定时 lockedUntil 为 null）。 */
    int updateLoginFailure(@Param("id") long id,
                           @Param("failedLoginCount") int failedLoginCount,
                           @Param("lockedUntil") LocalDateTime lockedUntil,
                           @Param("updatedAt") LocalDateTime updatedAt);

    /** 登录成功后清零失败计数；已是初始状态时不写库。 */
    int resetLoginFailures(@Param("id") long id, @Param("updatedAt") LocalDateTime updatedAt);
}
