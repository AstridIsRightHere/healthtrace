package com.healthtrace.report.mapper;

import com.healthtrace.report.domain.OcrJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * OCR_JOB 表访问。状态迁移全部是条件更新，返回 0 表示状态已被其他线程/实例改变，调用方必须放弃本次处理。
 */
@Mapper
public interface OcrJobMapper {

    /** 插入等待中的任务；同一报告已有等待/运行任务时触发唯一约束 uk_ocr_job_active_report。 */
    int insert(OcrJob job);

    OcrJob findById(@Param("id") long id);

    /** 报告最近一次任务（按 id 倒序）。 */
    OcrJob findLatestByReport(@Param("reportId") long reportId);

    /** 领取：PENDING 且已到重试时间 → RUNNING，attempt_count + 1。 */
    int claim(@Param("id") long id, @Param("now") LocalDateTime now);

    int markSucceeded(@Param("id") long id,
                      @Param("providerRequestId") String providerRequestId,
                      @Param("rawTextCiphertext") byte[] rawTextCiphertext,
                      @Param("rawExtractionCiphertext") byte[] rawExtractionCiphertext,
                      @Param("now") LocalDateTime now);

    /** RUNNING → PENDING，等待 nextRetryAt 后由调度重新领取。 */
    int scheduleRetry(@Param("id") long id,
                      @Param("errorCode") String errorCode,
                      @Param("nextRetryAt") LocalDateTime nextRetryAt,
                      @Param("now") LocalDateTime now);

    /** RUNNING → FAILED（终态，需用户手动重新识别或手动录入）。 */
    int markFailed(@Param("id") long id, @Param("errorCode") String errorCode, @Param("now") LocalDateTime now);

    /** 到期可领取的等待任务。 */
    List<Long> findDueIds(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /** 运行超时（进程崩溃等）的任务。 */
    List<OcrJob> findStuckRunning(@Param("startedBefore") LocalDateTime startedBefore, @Param("limit") int limit);

    /** 彻底删除报告时清理其全部任务记录（含加密原文）。 */
    int deleteByReport(@Param("reportId") long reportId);
}
