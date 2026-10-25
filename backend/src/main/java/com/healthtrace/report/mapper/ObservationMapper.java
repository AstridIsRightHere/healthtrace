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
}
