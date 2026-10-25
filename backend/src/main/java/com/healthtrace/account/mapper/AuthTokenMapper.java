package com.healthtrace.account.mapper;

import com.healthtrace.account.domain.AuthToken;
import com.healthtrace.account.domain.TokenPurpose;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * AUTH_TOKEN 表访问。只按摘要检索，原令牌不落库。
 */
@Mapper
public interface AuthTokenMapper {

    int insert(AuthToken token);

    AuthToken findByTokenDigest(@Param("tokenDigest") String tokenDigest);

    /**
     * 条件更新消耗令牌：仅当未消耗且未过期时成功，返回 1；重复消费或并发竞争返回 0。
     */
    int consume(@Param("id") long id, @Param("now") LocalDateTime now);

    /** 签发新令牌前作废同账号同用途的未消耗令牌，保证同一时刻只有一个有效链接。 */
    int invalidateOutstanding(@Param("accountId") long accountId,
                              @Param("purpose") TokenPurpose purpose,
                              @Param("now") LocalDateTime now);
}
