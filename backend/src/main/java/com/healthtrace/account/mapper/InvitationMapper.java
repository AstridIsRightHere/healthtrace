package com.healthtrace.account.mapper;

import com.healthtrace.account.domain.Invitation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * INVITATION 表访问。邀请码的签发属于受控人工流程（docs/08 设计边界），这里只提供检索与消耗。
 */
@Mapper
public interface InvitationMapper {

    Invitation findByTokenDigest(@Param("tokenDigest") String tokenDigest);

    /**
     * 条件更新消耗邀请码：仅当未使用且未过期时成功，返回 1；并发重复使用时返回 0。
     */
    int markUsed(@Param("id") long id,
                 @Param("usedBy") long usedBy,
                 @Param("now") LocalDateTime now);

    /**
     * 插入新邀请码（管理员生成，FR-018）。
     */
    void insert(Invitation invitation);

    /**
     * 列出所有邀请码（管理员查看，FR-018）。
     */
    List<Invitation> findAll();

    /**
     * 禁用邀请码（管理员操作，FR-018）：将过期时间设为指定时刻。
     */
    void setExpiresAt(@Param("id") long id, @Param("expiresAt") LocalDateTime expiresAt);
}
