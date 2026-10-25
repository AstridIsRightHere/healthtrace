package com.healthtrace.report.service;

import com.healthtrace.report.domain.Observation;
import com.healthtrace.report.domain.ObservationStatus;
import com.healthtrace.report.domain.OcrJob;
import com.healthtrace.report.domain.Photo;
import com.healthtrace.report.domain.ProcessingStatus;
import com.healthtrace.report.mapper.ObservationMapper;
import com.healthtrace.report.mapper.OcrJobMapper;
import com.healthtrace.report.mapper.PhotoMapper;
import com.healthtrace.report.mapper.ReportMapper;
import com.healthtrace.report.ocr.OcrConfig;
import com.healthtrace.report.ocr.OcrException;
import com.healthtrace.report.ocr.OcrModels.OcrInput;
import com.healthtrace.report.ocr.OcrModels.OcrItem;
import com.healthtrace.report.ocr.OcrModels.OcrResult;
import com.healthtrace.report.ocr.OcrService;
import com.healthtrace.report.ocr.OcrTextCipher;
import com.healthtrace.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

/**
 * OCR 任务执行器（FR-006、NFR-004）。
 * <p>
 * 状态机：PENDING →(claim) RUNNING → SUCCEEDED | PENDING(待重试) | FAILED。
 * 报告：RECOGNIZING → PENDING_CONFIRMATION（成功）| FAILED（终态失败）。
 * <ul>
 *   <li>所有迁移都是条件更新，多线程/多实例下同一任务只会被一个执行者领取。</li>
 *   <li>自动重试最多 {@value #MAX_ATTEMPTS} 次（含首次），退避 10s、30s；之后转为 FAILED，由用户手动重新识别或手动录入（docs/23 待决策）。</li>
 *   <li>进程崩溃导致长时间 RUNNING 的任务由调度扫描回收，按一次失败计入次数。</li>
 * </ul>
 */
@Component
public class OcrJobProcessor {

    private static final Logger log = LoggerFactory.getLogger(OcrJobProcessor.class);

    public static final int MAX_ATTEMPTS = 3;
    /** 第 n 次失败后的等待时间（n 从 1 开始）。 */
    static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(10), Duration.ofSeconds(30));
    static final Duration STUCK_AFTER = Duration.ofMinutes(2);
    static final String STUCK_ERROR = "OCR_TIMEOUT";
    private static final int SCAN_BATCH = 20;

    private final OcrJobMapper ocrJobMapper;
    private final ReportMapper reportMapper;
    private final PhotoMapper photoMapper;
    private final ObservationMapper observationMapper;
    private final OcrService ocrService;
    private final OcrTextCipher cipher;
    private final StorageService storageService;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OcrJobProcessor(OcrJobMapper ocrJobMapper, ReportMapper reportMapper, PhotoMapper photoMapper,
                           ObservationMapper observationMapper, OcrService ocrService, OcrTextCipher cipher,
                           StorageService storageService, PlatformTransactionManager transactionManager,
                           Clock clock) {
        this.ocrJobMapper = ocrJobMapper;
        this.reportMapper = reportMapper;
        this.photoMapper = photoMapper;
        this.observationMapper = observationMapper;
        this.ocrService = ocrService;
        this.cipher = cipher;
        this.storageService = storageService;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 提交后异步执行；线程池满被丢弃时任务仍是 PENDING，由 {@link #dispatchDue()} 补偿。 */
    @Async(OcrConfig.OCR_EXECUTOR)
    public void processAsync(long jobId) {
        process(jobId);
    }

    /** 每 5 秒领取到期的等待任务（含退避到期的重试）。 */
    @Scheduled(fixedDelay = 5_000, initialDelay = 10_000)
    public void dispatchDue() {
        for (Long id : ocrJobMapper.findDueIds(now(), SCAN_BATCH)) {
            process(id);
        }
    }

    /** 每 30 秒回收运行超时的任务。 */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void recoverStuck() {
        LocalDateTime now = now();
        for (OcrJob job : ocrJobMapper.findStuckRunning(now.minus(STUCK_AFTER), SCAN_BATCH)) {
            log.warn("OCR 任务运行超时，按失败处理：jobId={}", job.getId());
            handleFailure(job, STUCK_ERROR, true);
        }
    }

    /** 同步执行一个任务（测试与调度直接调用）。未领取到（已被他人处理/未到期）时直接返回。 */
    public void process(long jobId) {
        if (ocrJobMapper.claim(jobId, now()) == 0) {
            return;
        }
        OcrJob job = ocrJobMapper.findById(jobId);
        try {
            OcrResult result = ocrService.recognize(inputs(job.getReportId()));
            if (!saveSuccess(job, result)) {
                log.warn("OCR 结果未写入（任务或报告状态已变化）：jobId={}", jobId);
            }
        } catch (OcrException ex) {
            log.warn("OCR 识别失败：jobId={} attempt={} errorCode={}", jobId, job.getAttemptCount(), ex.errorCode());
            handleFailure(job, ex.errorCode(), ex.retryable());
        } catch (RuntimeException ex) {
            // 不记录异常消息：可能包含供应商响应片段
            log.error("OCR 任务异常：jobId={} type={}", jobId, ex.getClass().getSimpleName());
            handleFailure(job, OcrException.UNAVAILABLE, true);
        }
    }

    private List<OcrInput> inputs(long reportId) {
        List<Photo> photos = photoMapper.findCompleteByReport(reportId);
        return photos.stream()
                .map(p -> new OcrInput(p.getId(), p.getPageOrder(), p.getMediaType(), p.getObjectKey(),
                        () -> storageService.open(p.getObjectKey())))
                .toList();
    }

    private boolean saveSuccess(OcrJob job, OcrResult result) {
        LocalDateTime now = now();
        Boolean saved = tx.execute(status -> {
            int updated = ocrJobMapper.markSucceeded(job.getId(), result.providerRequestId(),
                    cipher.encrypt(result.rawText()), cipher.encrypt(result.rawExtraction()), now);
            if (updated == 0) {
                status.setRollbackOnly();
                return false;
            }
            if (reportMapper.completeRecognition(job.getReportId(), result.examinationDate(), now) == 0) {
                status.setRollbackOnly();
                return false;
            }
            // 重新识别时用最新结果替换旧草稿；正式记录不受影响
            observationMapper.deleteDrafts(job.getReportId());
            int order = 1;
            for (OcrItem item : result.items()) {
                observationMapper.insert(toDraft(job.getReportId(), item, order++, now));
            }
            return true;
        });
        return Boolean.TRUE.equals(saved);
    }

    private void handleFailure(OcrJob job, String errorCode, boolean retryable) {
        LocalDateTime now = now();
        int attempt = job.getAttemptCount();
        if (retryable && attempt < MAX_ATTEMPTS) {
            Duration wait = BACKOFF.get(Math.min(attempt, BACKOFF.size()) - 1);
            ocrJobMapper.scheduleRetry(job.getId(), errorCode, now.plus(wait), now);
            return;
        }
        tx.executeWithoutResult(status -> {
            if (ocrJobMapper.markFailed(job.getId(), errorCode, now) > 0) {
                reportMapper.transitionStatus(job.getReportId(), Set.of(ProcessingStatus.RECOGNIZING),
                        ProcessingStatus.FAILED, now);
            }
        });
    }

    static Observation toDraft(long reportId, OcrItem item, int rowOrder, LocalDateTime now) {
        Observation o = new Observation();
        o.setReportId(reportId);
        o.setOriginalName(truncate(item.name(), 160));
        o.setOriginalValue(truncate(item.valueText(), 160));
        o.setUnitText(truncate(item.unitText(), 80));
        o.setReferenceText(truncate(item.referenceText(), 160));
        o.setConfidence(item.confidence());
        o.setSourcePhotoId(item.sourcePhotoId());
        o.setStatus(ObservationStatus.DRAFT);
        o.setRowOrder(rowOrder);
        o.setCreatedAt(now);
        o.setUpdatedAt(now);
        ObservationParser.derive(o);
        return o;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.codePointCount(0, s.length()) <= max ? s : s.substring(0, s.offsetByCodePoints(0, max));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }
}
