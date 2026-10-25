package com.healthtrace.indicator.mapper;

import com.healthtrace.indicator.domain.MetricDefinition;
import com.healthtrace.indicator.domain.TrendObservation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 字典与趋势查询。只读取“已确认 + 正常可见”报告中的 FORMAL 观测值；调用方须先完成档案授权校验。
 */
@Mapper
public interface TrendMapper {

    List<MetricDefinition> findAllDefinitions();

    MetricDefinition findDefinitionByCode(@Param("code") String code);

    /** 该档案全部已映射标准指标的正式结果（用于“有数据的指标”列表）。 */
    List<TrendObservation> findFormalByProfile(@Param("profileId") long profileId);

    /** 指定指标截至 to（含）的全部正式结果；增减需要窗口之前的最近可比点，因此不设下界。 */
    List<TrendObservation> findFormalByMetric(@Param("profileId") long profileId,
                                              @Param("metricDefinitionId") long metricDefinitionId,
                                              @Param("to") LocalDate to);
}
