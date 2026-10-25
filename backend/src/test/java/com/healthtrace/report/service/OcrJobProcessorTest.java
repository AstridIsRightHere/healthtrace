package com.healthtrace.report.service;

import com.healthtrace.report.domain.Observation;
import com.healthtrace.report.domain.ObservationStatus;
import com.healthtrace.report.domain.OcrJob;
import com.healthtrace.report.domain.OcrJobStatus;
import com.healthtrace.report.domain.Photo;
import com.healthtrace.report.domain.ProcessingStatus;
import com.healthtrace.report.mapper.ObservationMapper;
import com.healthtrace.report.mapper.OcrJobMapper;
import com.healthtrace.report.mapper.PhotoMapper;
import com.healthtrace.report.mapper.ReportMapper;
import com.healthtrace.report.ocr.OcrException;
import com.healthtrace.report.ocr.OcrModels.OcrItem;
import com.healthtrace.report.ocr.OcrModels.OcrResult;
import com.healthtrace.report.ocr.OcrService;
import com.healthtrace.report.ocr.OcrTextCipher;
import com.healthtrace.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** OCR 任务状态机：领取、成功落库、可重试失败的退避、终态失败。 */
class OcrJobProcessorTest {

    private static final Instant NOW_INSTANT = Instant.parse("2026-10-25T10:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);
    private static final long JOB_ID = 7L;
    private static final long REPORT_ID = 3L;

    private OcrJobMapper ocrJobMapper;
    private ReportMapper reportMapper;
    private PhotoMapper photoMapper;
    private ObservationMapper observationMapper;
    private OcrService ocrService;
    private OcrTextCipher cipher;
    private OcrJobProcessor processor;

    @BeforeEach
    void setUp() {
        ocrJobMapper = mock(OcrJobMapper.class);
        reportMapper = mock(ReportMapper.class);
        photoMapper = mock(PhotoMapper.class);
        observationMapper = mock(ObservationMapper.class);
        ocrService = mock(OcrService.class);
        cipher = mock(OcrTextCipher.class);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        processor = new OcrJobProcessor(ocrJobMapper, reportMapper, photoMapper, observationMapper, ocrService,
                cipher, mock(StorageService.class), tm, Clock.fixed(NOW_INSTANT, ZoneOffset.UTC));

        Photo photo = new Photo();
        photo.setId(11L);
        photo.setReportId(REPORT_ID);
        photo.setPageOrder(1);
        photo.setMediaType("image/jpeg");
        photo.setObjectKey("k/1.jpg");
        when(photoMapper.findCompleteByReport(REPORT_ID)).thenReturn(List.of(photo));
        when(cipher.encrypt(anyString())).thenReturn(new byte[]{1});
    }

    private void claimedJob(int attemptAfterClaim) {
        OcrJob job = new OcrJob();
        job.setId(JOB_ID);
        job.setReportId(REPORT_ID);
        job.setStatus(OcrJobStatus.RUNNING);
        job.setAttemptCount(attemptAfterClaim);
        when(ocrJobMapper.claim(JOB_ID, NOW)).thenReturn(1);
        when(ocrJobMapper.findById(JOB_ID)).thenReturn(job);
    }

    @Test
    void notClaimedDoesNothing() throws Exception {
        when(ocrJobMapper.claim(JOB_ID, NOW)).thenReturn(0);
        processor.process(JOB_ID);
        verifyNoInteractions(ocrService);
        verify(ocrJobMapper, never()).markSucceeded(anyLong(), any(), any(), any(), any());
    }

    @Test
    void successWritesEncryptedTextDraftsAndMovesReportToPendingConfirmation() throws Exception {
        claimedJob(1);
        LocalDate date = LocalDate.of(2026, 10, 20);
        when(ocrService.recognize(any())).thenReturn(new OcrResult("log-1", "白细胞计数 6.5", "[]", date, List.of(
                new OcrItem("白细胞计数", "6.5", "10^9/L", "3.5-9.5", 11L, new BigDecimal("0.95000")),
                new OcrItem("C反应蛋白", "<0.5", "mg/L", "0-6", 11L, null))));
        when(ocrJobMapper.markSucceeded(eq(JOB_ID), eq("log-1"), any(), any(), eq(NOW))).thenReturn(1);
        when(reportMapper.completeRecognition(REPORT_ID, date, NOW)).thenReturn(1);

        processor.process(JOB_ID);

        verify(cipher).encrypt("白细胞计数 6.5");
        verify(observationMapper).deleteDrafts(REPORT_ID);
        ArgumentCaptor<Observation> captor = ArgumentCaptor.forClass(Observation.class);
        verify(observationMapper, times(2)).insert(captor.capture());
        Observation first = captor.getAllValues().get(0);
        assertThat(first.getStatus()).isEqualTo(ObservationStatus.DRAFT);
        assertThat(first.getRowOrder()).isEqualTo(1);
        assertThat(first.getNumericValue()).isEqualByComparingTo("6.5");
        assertThat(first.getConfidence()).isEqualByComparingTo("0.95");
        // 供应商没给置信度时保持 null，不伪造
        assertThat(captor.getAllValues().get(1).getConfidence()).isNull();
        verify(ocrJobMapper, never()).scheduleRetry(anyLong(), any(), any(), any());
    }

    @Test
    void retryableFailureSchedulesBackoff10sThen30s() throws Exception {
        claimedJob(1);
        when(ocrService.recognize(any())).thenThrow(new OcrException(OcrException.UNAVAILABLE, true, "x"));
        processor.process(JOB_ID);
        verify(ocrJobMapper).scheduleRetry(JOB_ID, OcrException.UNAVAILABLE, NOW.plusSeconds(10), NOW);

        claimedJob(2);
        processor.process(JOB_ID);
        verify(ocrJobMapper).scheduleRetry(JOB_ID, OcrException.UNAVAILABLE, NOW.plusSeconds(30), NOW);
        verify(ocrJobMapper, never()).markFailed(anyLong(), any(), any());
    }

    @Test
    void thirdFailureIsTerminalAndReportBecomesFailed() throws Exception {
        claimedJob(3);
        when(ocrService.recognize(any())).thenThrow(new OcrException(OcrException.UNAVAILABLE, true, "x"));
        when(ocrJobMapper.markFailed(JOB_ID, OcrException.UNAVAILABLE, NOW)).thenReturn(1);

        processor.process(JOB_ID);

        verify(ocrJobMapper, never()).scheduleRetry(anyLong(), any(), any(), any());
        verify(reportMapper).transitionStatus(REPORT_ID, Set.of(ProcessingStatus.RECOGNIZING),
                ProcessingStatus.FAILED, NOW);
    }

    @Test
    void nonRetryableFailureFailsImmediately() throws Exception {
        claimedJob(1);
        when(ocrService.recognize(any())).thenThrow(new OcrException(OcrException.AUTH_FAILED, false, "x"));
        when(ocrJobMapper.markFailed(JOB_ID, OcrException.AUTH_FAILED, NOW)).thenReturn(1);

        processor.process(JOB_ID);

        verify(ocrJobMapper, never()).scheduleRetry(anyLong(), any(), any(), any());
        verify(reportMapper).transitionStatus(REPORT_ID, Set.of(ProcessingStatus.RECOGNIZING),
                ProcessingStatus.FAILED, NOW);
    }

    @Test
    void unexpectedRuntimeErrorIsTreatedAsRetryable() throws Exception {
        claimedJob(1);
        when(ocrService.recognize(any())).thenThrow(new IllegalStateException("boom"));
        processor.process(JOB_ID);
        verify(ocrJobMapper).scheduleRetry(JOB_ID, OcrException.UNAVAILABLE, NOW.plusSeconds(10), NOW);
    }

    @Test
    void staleResultIsNotWrittenWhenJobNoLongerRunning() throws Exception {
        claimedJob(1);
        when(ocrService.recognize(any())).thenReturn(new OcrResult("log", "t", "[]", null,
                List.of(new OcrItem("a", "1", null, null, 11L, null))));
        when(ocrJobMapper.markSucceeded(anyLong(), any(), any(), any(), any())).thenReturn(0);

        processor.process(JOB_ID);

        verify(observationMapper, never()).insert(any());
        verify(reportMapper, never()).completeRecognition(anyLong(), any(), any());
    }
}
