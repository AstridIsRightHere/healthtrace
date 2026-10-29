package com.healthtrace.indicator.mapper;

import com.healthtrace.indicator.domain.MetricAggregate;
import com.healthtrace.indicator.domain.MetricDefinition;
import com.healthtrace.indicator.domain.TrendObservation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.Collection;
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

    /**
     * 该档案正式结果的分组聚合（用于“有数据的指标”列表）：按标准 code（未映射时 + 报告原名）
     * 与单位/标本/方法的原始写法分组计数，归一与合并在服务层完成。
     */
    List<MetricAggregate> aggregateFormalByProfile(@Param("profileId") long profileId);

    /** 指定指标在 [from, to]（含）内的全部正式结果，按日期、id 升序。 */
    List<TrendObservation> findFormalByMetric(@Param("profileId") long profileId,
                                              @Param("metricDefinitionId") long metricDefinitionId,
                                              @Param("from") LocalDate from,
                                              @Param("to") LocalDate to);

    /**
     * 指定指标在 before 之前（不含）计算窗口所需的最少历史：每种原始写法的最早一条，
     * 以及精确数值结果最近一个日期的全部行。用于可比组排序/展示写法与窗口首点的增减。
     */
    List<TrendObservation> findFormalHistoryByMetric(@Param("profileId") long profileId,
                                                     @Param("metricDefinitionId") long metricDefinitionId,
                                                     @Param("before") LocalDate before);

    /** 多个指标截至 to（含）的精确数值结果，每个指标每种原始写法只取最近 2 个日期；行带 metricCode。 */
    List<TrendObservation> findRecentExactByMetrics(@Param("profileId") long profileId,
                                                    @Param("metricDefinitionIds") Collection<Long> metricDefinitionIds,
                                                    @Param("to") LocalDate to);
}
