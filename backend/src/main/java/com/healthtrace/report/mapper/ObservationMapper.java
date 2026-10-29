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

    /** 报告全部结果行（含已替代 / 已撤回），按 id 升序；用于追溯版本链。 */
    List<Observation> findAllByReport(@Param("reportId") long reportId);

    /** 报告内最大行序（含失效行）；无行时返回 -1。 */
    int maxRowOrder(@Param("reportId") long reportId);

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

    /** 彻底删除报告前解除结果行之间的版本链（supersedes_id），避免自引用外键阻止整表删除。 */
    int unlinkSupersedes(@Param("reportId") long reportId);

    /** 彻底删除报告时清理其全部结果行（任意状态）；须在删除 report_revision 之前执行。 */
    int deleteByReport(@Param("reportId") long reportId);

    /**
     * FR-014：使一条正式结果失效（SUPERSEDED / RETRACTED），只改状态与失效信息，原值保持不变。
     * 只作用于 FORMAL；返回 0 表示该行已不是正式结果（并发修改）。
     */
    int retireFormal(@Param("reportId") long reportId, @Param("id") long id,
                     @Param("status") ObservationStatus status, @Param("revisionId") long revisionId,
                     @Param("retiredAt") LocalDateTime retiredAt);

    /** FR-014：为本次修改新插入的结果行回填 created_revision_id。 */
    int assignCreatedRevision(@Param("reportId") long reportId, @Param("ids") List<Long> ids,
                              @Param("revisionId") long revisionId);

    /** FR-014：检查日期修改后同步到该报告当前的全部正式结果。 */
    int updateFormalExaminationDate(@Param("reportId") long reportId,
                                    @Param("examinationDate") LocalDate examinationDate,
                                    @Param("updatedAt") LocalDateTime updatedAt);
}
