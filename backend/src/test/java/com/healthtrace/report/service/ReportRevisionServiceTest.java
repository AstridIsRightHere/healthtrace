package com.healthtrace.report.service;

import com.healthtrace.common.security.FieldCipher;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.report.domain.Observation;
import com.healthtrace.report.domain.ObservationStatus;
import com.healthtrace.report.domain.Photo;
import com.healthtrace.report.domain.ProcessingStatus;
import com.healthtrace.report.domain.Report;
import com.healthtrace.report.domain.ReportRevision;
import com.healthtrace.report.mapper.ObservationMapper;
import com.healthtrace.report.mapper.OcrJobMapper;
import com.healthtrace.report.mapper.PhotoMapper;
import com.healthtrace.report.mapper.ReportMapper;
import com.healthtrace.report.mapper.ReportRevisionMapper;
import com.healthtrace.report.ocr.OcrService;
import com.healthtrace.report.ocr.OcrTextCipher;
import com.healthtrace.report.service.ReviewViews.ObservationInput;
import com.healthtrace.report.service.RevisionViews.ChangeType;
import com.healthtrace.report.service.RevisionViews.ReviseCommand;
import com.healthtrace.report.service.RevisionViews.ReviseResult;
import com.healthtrace.report.service.RevisionViews.RevisionView;
import com.healthtrace.rights.audit.AuditAction;
import com.healthtrace.rights.audit.AuditService;
import com.healthtrace.rights.consent.ConsentService;
import com.healthtrace.profile.mapper.ProfileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ReportRevisionServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 20);

    private ReportAccess access;
    private ReportMapper reportMapper;
    private PhotoMapper photoMapper;
    private ObservationMapper observationMapper;
    private ReportRevisionMapper revisionMapper;
    private AuditService auditService;
    private OcrTextCipher cipher;
    private ReportRevisionService service;

    @BeforeEach
    void setUp() {
        access = mock(ReportAccess.class);
        reportMapper = mock(ReportMapper.class);
        photoMapper = mock(PhotoMapper.class);
        observationMapper = mock(ObservationMapper.class);
        revisionMapper = mock(ReportRevisionMapper.class);
        auditService = mock(AuditService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-27T08:00:00Z"), ZoneOffset.UTC);
        byte[] key = Base64.getDecoder().decode("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        cipher = new OcrTextCipher(newFieldCipher(key));
        ReviewService reviewService = new ReviewService(access, reportMapper, photoMapper, mock(OcrJobMapper.class),
                observationMapper, mock(OcrService.class), cipher, mock(OcrJobProcessor.class),
                mock(ProfileMapper.class), mock(ConsentService.class), auditService, clock);
        service = new ReportRevisionService(access, reportMapper, photoMapper, observationMapper, revisionMapper,
                reviewService, cipher, JsonMapper.builder().build(), auditService, clock);
    }

    private static FieldCipher newFieldCipher(byte[] key) {
        return new FieldCipher(key);
    }

    private Report confirmed(long version) {
        Report r = new Report();
        r.setId(21L);
        r.setProcessingStatus(ProcessingStatus.CONFIRMED);
        r.setVersion(version);
        r.setExaminationDate(DATE);
        given(access.requireOwnedReport(7L, 21L)).willReturn(r);
        given(reportMapper.findByIdForUpdate(21L)).willReturn(r);
        return r;
    }

    private static Observation formal(long id, String name, String value) {
        Observation o = new Observation();
        o.setId(id);
        o.setReportId(21L);
        o.setStatus(ObservationStatus.FORMAL);
        o.setOriginalName(name);
        o.setOriginalValue(value);
        o.setUnitText("g/L");
        o.setReferenceText("130-175");
        o.setExaminationDate(DATE);
        return o;
    }

    private void formalRows(Observation... rows) {
        given(observationMapper.findByReport(21L, ObservationStatus.FORMAL)).willReturn(List.of(rows));
        Photo p = new Photo();
        p.setId(5L);
        given(photoMapper.findCompleteByReport(21L)).willReturn(List.of(p));
    }

    private static ObservationInput in(Long id, String name, String value) {
        return new ObservationInput(id, name, value, "g/L", "130-175", null);
    }

    private void insertAssignsId() {
        willAnswer(inv -> {
            ((ReportRevision) inv.getArgument(0)).setId(99L);
            return 1;
        }).given(revisionMapper).insert(any());
        given(reportMapper.revise(21L, 3L, DATE, java.time.LocalDateTime.of(2026, 10, 27, 8, 0))).willReturn(1);
    }

    @Test
    void othersReportIsNotFoundAndNothingWritten() {
        willThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND)).given(access).requireOwnedReport(7L, 88L);
        assertThatThrownBy(() -> service.revise(7L, 88L, new ReviseCommand(1L, DATE, null,
                List.of(in(null, "血红蛋白", "140")))))
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        verify(observationMapper, never()).updateFormal(any());
        verify(revisionMapper, never()).insert(any());
    }

    @Test
    void listRevisionsOfOthersReportIsNotFound() {
        willThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND)).given(access).requireOwnedReport(7L, 88L);
        assertThatThrownBy(() -> service.listRevisions(7L, 88L))
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        verify(revisionMapper, never()).findByReport(anyLong());
    }

    @Test
    void draftReportCannotBeRevised() {
        Report r = confirmed(3L);
        r.setProcessingStatus(ProcessingStatus.PENDING_CONFIRMATION);
        formalRows();
        assertThatThrownBy(() -> service.revise(7L, 21L, new ReviseCommand(3L, DATE, null,
                List.of(in(null, "血红蛋白", "140")))))
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.REPORT_STATE_CONFLICT);
    }

    @Test
    void staleVersionConflicts() {
        confirmed(3L);
        formalRows(formal(1L, "血红蛋白", "140"));
        assertThatThrownBy(() -> service.revise(7L, 21L, new ReviseCommand(2L, DATE, null,
                List.of(in(1L, "血红蛋白", "150")))))
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT);
        verify(observationMapper, never()).updateFormal(any());
    }

    @Test
    void noChangeIsRejected() {
        confirmed(3L);
        formalRows(formal(1L, "血红蛋白", "140"));
        assertThatThrownBy(() -> service.revise(7L, 21L, new ReviseCommand(3L, DATE, null,
                List.of(in(1L, "血红蛋白", "140")))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.getFieldErrors()).extracting("reasonCode").containsExactly("NO_CHANGE");
                });
        verify(revisionMapper, never()).insert(any());
    }

    @Test
    void unknownRowIdIsRejected() {
        confirmed(3L);
        formalRows(formal(1L, "血红蛋白", "140"));
        assertThatThrownBy(() -> service.revise(7L, 21L, new ReviseCommand(3L, DATE, null,
                List.of(in(555L, "血红蛋白", "150")))))
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void tooLongReasonIsRejected() {
        confirmed(3L);
        formalRows(formal(1L, "血红蛋白", "140"));
        String reason = "改".repeat(ReportRevisionService.REASON_MAX + 1);
        assertThatThrownBy(() -> service.revise(7L, 21L, new ReviseCommand(3L, DATE, reason,
                List.of(in(1L, "血红蛋白", "150")))))
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void reviseWritesDiffRevisionAndAudit() {
        confirmed(3L);
        formalRows(formal(1L, "血红蛋白", "140"), formal(2L, "白细胞", "6.1"));
        insertAssignsId();

        ReviseResult result = service.revise(7L, 21L, new ReviseCommand(3L, DATE, " 录入错误 ",
                List.of(in(1L, "血红蛋白", "150"), in(null, "血小板", "210"))));

        assertThat(result.version()).isEqualTo(4L);
        assertThat(result.revisionId()).isEqualTo(99L);
        assertThat(result.changedCount()).isEqualTo(3); // UPDATED + ADDED + REMOVED
        verify(observationMapper).deleteFormalById(21L, 2L);
        verify(observationMapper).updateFormal(any());
        verify(observationMapper).insert(any());
        verify(observationMapper).assignMetricDefinitions(21L);
        verify(auditService).success(7L, AuditAction.REPORT_REVISE, "report:21");

        ArgumentCaptor<ReportRevision> captor = ArgumentCaptor.forClass(ReportRevision.class);
        verify(revisionMapper).insert(captor.capture());
        ReportRevision saved = captor.getValue();
        assertThat(saved.getFromVersion()).isEqualTo(3L);
        assertThat(saved.getToVersion()).isEqualTo(4L);
        assertThat(saved.getReason()).isEqualTo("录入错误");
        // 差异正文加密存储：密文中不得出现明文旧值
        assertThat(new String(saved.getChangePayload(), java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("血红蛋白");

        // 读回：解密后可还原修改前后
        given(revisionMapper.findByReport(21L)).willReturn(List.of(saved));
        List<RevisionView> views = service.listRevisions(7L, 21L);
        assertThat(views).hasSize(1);
        assertThat(views.getFirst().changes()).extracting(c -> c.type())
                .containsExactlyInAnyOrder(ChangeType.UPDATED, ChangeType.ADDED, ChangeType.REMOVED);
        assertThat(views.getFirst().changes()).filteredOn(c -> c.type() == ChangeType.UPDATED)
                .first().satisfies(c -> {
                    assertThat(c.before().originalValue()).isEqualTo("140");
                    assertThat(c.after().originalValue()).isEqualTo("150");
                });
        assertThat(views.getFirst().examinationDate()).isNull();
    }

    @Test
    void dateOnlyChangeIsRecorded() {
        confirmed(3L);
        formalRows(formal(1L, "血红蛋白", "140"));
        LocalDate newDate = DATE.minusDays(1);
        given(reportMapper.revise(anyLong(), anyLong(), any(), any())).willReturn(1);
        willAnswer(inv -> {
            ((ReportRevision) inv.getArgument(0)).setId(100L);
            return 1;
        }).given(revisionMapper).insert(any());

        ReviseResult result = service.revise(7L, 21L, new ReviseCommand(3L, newDate, null,
                List.of(in(1L, "血红蛋白", "140"))));

        assertThat(result.changedCount()).isZero();
        assertThat(result.examinationDate()).isEqualTo(newDate);
        ArgumentCaptor<Observation> obs = ArgumentCaptor.forClass(Observation.class);
        verify(observationMapper).updateFormal(obs.capture());
        assertThat(obs.getValue().getExaminationDate()).isEqualTo(newDate);
    }

    @Test
    void concurrentRevisionMapsToVersionConflict() {
        confirmed(3L);
        formalRows(formal(1L, "血红蛋白", "140"));
        given(reportMapper.revise(anyLong(), anyLong(), any(), any())).willReturn(1);
        willThrow(new DuplicateKeyException("uk")).given(revisionMapper).insert(any());
        assertThatThrownBy(() -> service.revise(7L, 21L, new ReviseCommand(3L, DATE, null,
                List.of(in(1L, "血红蛋白", "150")))))
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT);
        verify(auditService, never()).success(anyLong(), any(), any());
    }
}
