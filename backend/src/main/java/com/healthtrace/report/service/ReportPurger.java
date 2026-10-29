package com.healthtrace.report.service;

import com.healthtrace.report.domain.Photo;
import com.healthtrace.report.domain.Report;
import com.healthtrace.report.mapper.ObservationMapper;
import com.healthtrace.report.mapper.OcrJobMapper;
import com.healthtrace.report.mapper.PhotoMapper;
import com.healthtrace.report.mapper.ReportMapper;
import com.healthtrace.report.mapper.ReportRevisionMapper;
import com.healthtrace.rights.deletion.DeletionEventType;
import com.healthtrace.rights.deletion.DeletionLedgerService;
import com.healthtrace.visit.mapper.VisitMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 彻底删除一份报告及其全部从属数据（FR-016，账号注销 FR-017 复用）。
 * <ul>
 *   <li>不做权限判断：调用方先完成归属校验；必须在调用方事务内执行。</li>
 *   <li>任意处理状态都可删除，含已确认与识别中：OCR 任务行一并删除，执行中的任务提交结果时会因行不存在而放弃。</li>
 *   <li>删除顺序满足外键：就诊关联 → 结果（先解除行间版本链；结果引用图片与修改记录）→ 修改记录 → OCR 任务 → 图片 → 报告。</li>
 *   <li>图片原图与预览的对象键写入存储清理 outbox，提交后删除；每份报告写一条 REPORT_PURGE 账本。</li>
 * </ul>
 */
@Service
public class ReportPurger {

    private final ReportMapper reportMapper;
    private final ReportRevisionMapper revisionMapper;
    private final VisitMapper visitMapper;
    private final ObservationMapper observationMapper;
    private final OcrJobMapper ocrJobMapper;
    private final PhotoMapper photoMapper;
    private final DeletionLedgerService ledgerService;

    public ReportPurger(ReportMapper reportMapper, ReportRevisionMapper revisionMapper, VisitMapper visitMapper,
                        ObservationMapper observationMapper, OcrJobMapper ocrJobMapper, PhotoMapper photoMapper,
                        DeletionLedgerService ledgerService) {
        this.reportMapper = reportMapper;
        this.revisionMapper = revisionMapper;
        this.visitMapper = visitMapper;
        this.observationMapper = observationMapper;
        this.ocrJobMapper = ocrJobMapper;
        this.photoMapper = photoMapper;
        this.ledgerService = ledgerService;
    }

    /** 删除报告；返回删除的数据库行数，报告不存在（已被并发删除）时返回 0 且不写账本。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public int purge(long reportId) {
        Report report = reportMapper.findByIdForUpdate(reportId);
        if (report == null) {
            return 0;
        }
        List<Photo> photos = photoMapper.findAllKeysByReport(reportId);
        int removed = visitMapper.unlinkReport(reportId);
        observationMapper.unlinkSupersedes(reportId);
        removed += observationMapper.deleteByReport(reportId);
        removed += revisionMapper.deleteByReport(reportId);
        removed += ocrJobMapper.deleteByReport(reportId);
        removed += photoMapper.deleteByReport(reportId);
        removed += reportMapper.deleteById(reportId);

        List<String> keys = new ArrayList<>();
        for (Photo photo : photos) {
            if (photo.getObjectKey() != null) {
                keys.add(photo.getObjectKey());
            }
            if (photo.getPreviewObjectKey() != null) {
                keys.add(photo.getPreviewObjectKey());
            }
        }
        ledgerService.recordWithStorage(DeletionEventType.REPORT_PURGE, reportId, removed, keys);
        return removed;
    }
}
