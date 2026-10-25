package com.healthtrace.report.mapper;

import com.healthtrace.report.domain.ProcessingStatus;
import com.healthtrace.report.domain.Report;
import com.healthtrace.report.domain.ReportSummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/** REPORT 表访问。调用方须先完成档案归属校验，这里不做权限判断。 */
@Mapper
public interface ReportMapper {

    int insert(Report report);

    Report findById(@Param("id") long id);

    /** 待处理（DRAFT 别名）：visibility=NORMAL 且 processing_status≠CONFIRMED，按上传时间倒序。 */
    List<ReportSummary> findPendingByProfile(@Param("profileId") long profileId);

    /** 带行锁读取，用于确认等需要串行化的写操作。 */
    Report findByIdForUpdate(@Param("id") long id);

    /**
     * 条件迁移处理状态：仅当当前状态在 fromStatuses 内时更新，返回受影响行数（0 = 状态已变化）。
     */
    int transitionStatus(@Param("id") long id,
                         @Param("fromStatuses") Collection<ProcessingStatus> fromStatuses,
                         @Param("toStatus") ProcessingStatus toStatus,
                         @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 识别完成：RECOGNIZING → PENDING_CONFIRMATION；报告尚无检查日期时采用识别出的日期（仍需用户确认）。
     */
    int completeRecognition(@Param("id") long id,
                            @Param("examinationDate") LocalDate examinationDate,
                            @Param("updatedAt") LocalDateTime updatedAt);

    /** 确认报告（乐观锁）：version 不符或状态不是待确认时返回 0。 */
    int confirm(@Param("id") long id,
                @Param("expectedVersion") long expectedVersion,
                @Param("examinationDate") LocalDate examinationDate,
                @Param("confirmedBy") long confirmedBy,
                @Param("confirmedAt") LocalDateTime confirmedAt);

    /** 物理删除未确认（UPLOADING / PENDING_CONFIRMATION / FAILED）的报告；返回 0 表示状态已变化。 */
    int deleteUnconfirmed(@Param("id") long id);
}
