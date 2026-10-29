package com.healthtrace.indicator.mapper;

import com.healthtrace.indicator.domain.FollowedMetric;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 档案关注指标持久化（FR-009）。调用方须先完成档案授权校验。 */
@Mapper
public interface FollowedMetricMapper {

    /** 按 sort_order 升序返回该档案的关注指标，带字典标准名称。 */
    List<FollowedMetric> findByProfile(@Param("profileId") long profileId);

    int deleteByProfile(@Param("profileId") long profileId);

    /** 按列表顺序插入，sort_order = 下标；codes 不能为空列表（空列表时调用方跳过）。 */
    int insertAll(@Param("profileId") long profileId,
                  @Param("codes") List<String> codes,
                  @Param("createdAt") LocalDateTime createdAt);
}
