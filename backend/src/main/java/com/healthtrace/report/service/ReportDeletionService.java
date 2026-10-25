package com.healthtrace.report.service;

import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.report.domain.Photo;
import com.healthtrace.report.domain.ProcessingStatus;
import com.healthtrace.report.domain.Report;
import com.healthtrace.report.mapper.ObservationMapper;
import com.healthtrace.report.mapper.OcrJobMapper;
import com.healthtrace.report.mapper.PhotoMapper;
import com.healthtrace.report.mapper.ReportMapper;
import com.healthtrace.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 彻底删除本人名下、尚未确认的报告（含图片、OCR 任务与草稿观测值）。
 * <ul>
 *   <li>已确认（CONFIRMED）的报告已进入正式数据，不走此接口；识别中（RECOGNIZING）需等待任务结束。</li>
 *   <li>数据库记录在同一事务内删除；存储对象在提交后清理，清理失败只记录对象 id，不回滚业务删除。</li>
 * </ul>
 */
@Service
public class ReportDeletionService {

    private static final Logger log = LoggerFactory.getLogger(ReportDeletionService.class);

    static final Set<ProcessingStatus> DELETABLE = EnumSet.of(
            ProcessingStatus.UPLOADING, ProcessingStatus.PENDING_CONFIRMATION, ProcessingStatus.FAILED);

    private final ReportAccess access;
    private final ReportMapper reportMapper;
    private final PhotoMapper photoMapper;
    private final OcrJobMapper ocrJobMapper;
    private final ObservationMapper observationMapper;
    private final StorageService storageService;

    public ReportDeletionService(ReportAccess access, ReportMapper reportMapper, PhotoMapper photoMapper,
                                 OcrJobMapper ocrJobMapper, ObservationMapper observationMapper,
                                 StorageService storageService) {
        this.access = access;
        this.reportMapper = reportMapper;
        this.photoMapper = photoMapper;
        this.ocrJobMapper = ocrJobMapper;
        this.observationMapper = observationMapper;
        this.storageService = storageService;
    }

    @Transactional
    public void deleteUnconfirmed(long accountId, long reportId) {
        access.requireOwnedReport(accountId, reportId);
        Report report = reportMapper.findByIdForUpdate(reportId);
        if (report == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!DELETABLE.contains(report.getProcessingStatus())) {
            throw new ApiException(ErrorCode.REPORT_STATE_CONFLICT,
                    report.getProcessingStatus() == ProcessingStatus.RECOGNIZING
                            ? "报告正在识别中，请等待识别结束后再删除"
                            : "已确认的报告不能在这里删除");
        }

        List<Photo> photos = photoMapper.findAllKeysByReport(reportId);
        observationMapper.deleteByReport(reportId);
        ocrJobMapper.deleteByReport(reportId);
        photoMapper.deleteByReport(reportId);
        if (reportMapper.deleteUnconfirmed(reportId) == 0) {
            // 行锁下理论上不会发生；兜底防止误删已变更状态的报告
            throw new ApiException(ErrorCode.REPORT_STATE_CONFLICT);
        }

        List<String> keys = new ArrayList<>();
        for (Photo photo : photos) {
            if (photo.getObjectKey() != null) {
                keys.add(photo.getObjectKey());
            }
            if (photo.getPreviewObjectKey() != null) {
                keys.add(photo.getPreviewObjectKey());
            }
        }
        runAfterCommit(() -> purge(reportId, keys));
    }

    private void purge(long reportId, List<String> keys) {
        for (String key : keys) {
            try {
                storageService.delete(key);
            } catch (RuntimeException e) {
                // 不记录对象键以外的业务内容；残留对象可由后续清理任务回收
                log.warn("Failed to purge storage object after report deletion, reportId={}, type={}",
                        reportId, e.getClass().getSimpleName());
            }
        }
    }

    private static void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
