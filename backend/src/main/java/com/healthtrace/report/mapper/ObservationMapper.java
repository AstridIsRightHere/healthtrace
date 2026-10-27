package com.healthtrace.report.mapper;

import com.healthtrace.report.domain.Observation;
import com.healthtrace.report.domain.ObservationStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** OBSERVATION 表访问。调用方须先完成报告归属校验。 */
@Mapper
public interface ObservationMapper {

    int insert(Observation observation);

    List<Observation> findByReport(@Param("reportId") long reportId, @Param("status") ObservationStatus status);

    /** 更新草稿行的可编辑字段（只作用于 DRAFT）。 */
    int updateDraft(Observation observation);

    int deleteDraftById(@Param("reportId") long reportId, @Param("id") long id);

    int deleteDrafts(@Param("reportId") long reportId);

    /** 报告全部草稿转为正式，写入统一检查日期与确认时间。 */
    int confirmDrafts(@Param("reportId") long reportId,
                      @Param("examinationDate") LocalDate examinationDate,
                      @Param("confirmedAt") LocalDateTime confirmedAt);

    /**
     * 按 METRIC_ALIAS 把该报告的正式结果映射到标准指标（规则与 V5 回填一致：只匹配命名，不换算数值）。
     * 未命中的结果保持 metric_definition_id 为空，不进入标准指标趋势。
     */
    int assignMetricDefinitions(@Param("reportId") long reportId);

    /** 彻底删除未确认报告时清理其全部观测值（此时只可能是草稿）。 */
    int deleteByReport(@Param("reportId") long reportId);

    /** FR-014：更新正式结果的可编辑字段并清空标准指标映射（只作用于 FORMAL）。 */
    int updateFormal(Observation observation);

    /** FR-014：修改时移除某一正式结果；旧值保存在 report_revision。 */
    int deleteFormalById(@Param("reportId") long reportId, @Param("id") long id);
}
