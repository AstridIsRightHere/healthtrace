package com.healthtrace.indicator.mapper;

import com.healthtrace.indicator.domain.FollowedMetric;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 档案关注指标持久化（FR-009）。调用方须先完成档案授权校验。 */
@Mapper
public interface FollowedMetricMapper {

    /**
     * 按 sort_order 升序返回该档案的关注指标：标准指标带字典名称与标准单位，
     * 临时指标 code 为 "TMP:{id}"，带临时指标展示名与单位。
     */
    List<FollowedMetric> findByProfile(@Param("profileId") long profileId);

    int deleteByProfile(@Param("profileId") long profileId);

    /**
     * 按列表顺序插入，sort_order = 下标；codes 不能为空列表（空列表时调用方跳过）。
     * "TMP:{id}" 写入 temp_metric_id，其余写入 metric_code；调用方须先校验 code 合法且临时指标属于该档案。
     */
    int insertAll(@Param("profileId") long profileId,
                  @Param("codes") List<String> codes,
                  @Param("createdAt") LocalDateTime createdAt);

    int countByProfile(@Param("profileId") long profileId);

    /** 关注列表末尾追加一个临时指标（sort_order = 当前最大值 + 1）。 */
    int appendTempMetric(@Param("profileId") long profileId,
                         @Param("tempMetricId") long tempMetricId,
                         @Param("createdAt") LocalDateTime createdAt);

    int deleteByTempMetric(@Param("tempMetricId") long tempMetricId);
}
