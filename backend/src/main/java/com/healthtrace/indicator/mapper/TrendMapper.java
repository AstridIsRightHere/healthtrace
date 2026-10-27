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

    /**
     * 该档案全部正式结果（用于“有数据的指标”列表）。已映射标准字典的行带 metricCode；
     * 未映射的行 metricCode 为空，由服务层按报告原名归为“其他项目”。
     */
    List<TrendObservation> findFormalByProfile(@Param("profileId") long profileId);

    /** 单份报告的全部 FORMAL 结果（按观测 id 升序），已映射的行带 metricCode；调用方须先校验报告授权与确认状态。 */
    List<TrendObservation> findFormalByReport(@Param("reportId") long reportId);

    /** 未映射标准字典的正式结果（截至 to，含）；按原名筛选在服务层完成，与列表归组规则一致。 */
    List<TrendObservation> findFormalUnmapped(@Param("profileId") long profileId,
                                              @Param("to") LocalDate to);

    /** 指定指标截至 to（含）的全部正式结果；增减需要窗口之前的最近可比点，因此不设下界。 */
    List<TrendObservation> findFormalByMetric(@Param("profileId") long profileId,
                                              @Param("metricDefinitionId") long metricDefinitionId,
                                              @Param("to") LocalDate to);
}
