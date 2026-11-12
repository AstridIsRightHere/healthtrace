package com.healthtrace.indicator.mapper;

import com.healthtrace.indicator.domain.TempMetric;
import com.healthtrace.indicator.domain.TempMetricCandidate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/** 临时指标持久化（FR-009，DEC-015）。调用方须先完成档案授权校验。 */
@Mapper
public interface TempMetricMapper {

    /** 结果行及其报告状态、所属档案；不存在返回 null。 */
    TempMetricCandidate findCandidate(@Param("observationId") long observationId);

    /** 该档案尚未映射（标准指标与临时指标均为空）的全部正式结果，归并键由服务层计算。 */
    List<TempMetricCandidate> findUnassignedFormalByProfile(@Param("profileId") long profileId);

    /** 单份报告尚未映射的正式结果（确认 / 修改后自动归并用）。 */
    List<TempMetricCandidate> findUnassignedFormalByReport(@Param("reportId") long reportId);

    TempMetric findByKey(@Param("profileId") long profileId,
                         @Param("nameKey") String nameKey,
                         @Param("unitKey") String unitKey);

    /** 带结果数与关注标记；按 id 升序。 */
    List<TempMetric> findByProfile(@Param("profileId") long profileId);

    /** 带结果数与关注标记；不属于该档案时返回 null。 */
    TempMetric findById(@Param("profileId") long profileId, @Param("id") long id);

    int insert(TempMetric metric);

    /** 只改仍未映射的行，避免覆盖标准指标或其他临时指标。 */
    int assignObservations(@Param("tempMetricId") long tempMetricId,
                           @Param("ids") Collection<Long> observationIds,
                           @Param("updatedAt") LocalDateTime updatedAt);

    /** 解除全部结果行（含已替代 / 已撤回的历史行）对该临时指标的映射。 */
    int unassignObservations(@Param("tempMetricId") long tempMetricId);

    /** 已归并多条结果：mapping_status 置为 MERGED（SEPARATE 不改）。 */
    int markMerged(@Param("id") long id, @Param("updatedAt") LocalDateTime updatedAt);

    /** 不带统计列的全部临时指标（确认报告后自动归并用）。 */
    List<TempMetric> findKeysByProfile(@Param("profileId") long profileId);

    int deleteById(@Param("profileId") long profileId, @Param("id") long id);
}
