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
import com.healthtrace.report.mapper.PhotoMapper;
import com.healthtrace.report.mapper.ReportMapper;
import com.healthtrace.report.mapper.ReportRevisionMapper;
import com.healthtrace.report.ocr.OcrTextCipher;
import com.healthtrace.report.service.ReviewViews.ObservationInput;
import com.healthtrace.report.service.RevisionViews.ChangeType;
import com.healthtrace.report.service.RevisionViews.CreateRevisionCommand;
import com.healthtrace.report.service.RevisionViews.ObservationVersionView;
import com.healthtrace.report.service.RevisionViews.OperationType;
import com.healthtrace.report.service.RevisionViews.ReviseResult;
import com.healthtrace.report.service.RevisionViews.RevisionChange;
import com.healthtrace.report.service.RevisionViews.RevisionOperation;
import com.healthtrace.report.service.RevisionViews.RevisionView;
import com.healthtrace.rights.audit.AuditAction;
import com.healthtrace.rights.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ReportRevisionServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 20);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 27, 8, 0);

    private ReportAccess access;
    private ReportMapper reportMapper;
    private PhotoMapper photoMapper;
    private ObservationMapper observationMapper;
    private ReportRevisionMapper revisionMapper;
    private AuditService auditService;
    private ReportRevisionService service;
    private final List<Observation> inserted = new ArrayList<>();

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
        OcrTextCipher cipher = new OcrTextCipher(new FieldCipher(key));
        service = new ReportRevisionService(access, reportMapper, photoMapper, observationMapper, revisionMapper,
                cipher, JsonMapper.builder().build(), auditService, clock);
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
        o.setSourcePhotoId(5L);
        o.setRowOrder((int) id - 1);
        return o;
    }

    private void formalRows(Observation... rows) {
        given(observationMapper.findByReport(21L, ObservationStatus.FORMAL)).willReturn(List.of(rows));
        Photo p = new Photo();
        p.setId(5L);
        Photo q = new Photo();
        q.setId(6L);
        given(photoMapper.findCompleteByReport(21L)).willReturn(List.of(p, q));
    }

    /** 让写库步骤全部成功：版本 +1、新行自增 id 从 100 起、修改记录 id=99、旧行失效 1 行。 */
    private void writesSucceed() {
        given(reportMapper.revise(eq(21L), anyLong(), any(), any())).willReturn(1);
        given(observationMapper.maxRowOrder(21L)).willReturn(1);
        AtomicLong ids = new AtomicLong(100);
        willAnswer(inv -> {
            Observation o = inv.getArgument(0);
            o.setId(ids.getAndIncrement());
            inserted.add(o);
            return 1;
        }).given(observationMapper).insert(any());
        willAnswer(inv -> {
            ((ReportRevision) inv.getArgument(0)).setId(99L);
            return 1;
        }).given(revisionMapper).insert(any());
        given(observationMapper.retireFormal(eq(21L), anyLong(), any(), eq(99L), any())).willReturn(1);
    }

    private static ObservationInput in(String name, String value) {
        return new ObservationInput(null, name, value, "g/L", "130-175", null);
    }

    private static RevisionOperation add(ObservationInput in) {
        return new RevisionOperation(OperationType.ADD, null, in);
    }

    private static RevisionOperation update(long id, ObservationInput in) {
        return new RevisionOperation(OperationType.UPDATE, id, in);
    }

    private static RevisionOperation delete(long id) {
        return new RevisionOperation(OperationType.DELETE, id, null);
    }

    private static CreateRevisionCommand cmd(long version, RevisionOperation... ops) {
        return new CreateRevisionCommand(version, null, null, Arrays.asList(ops));
    }

    private static void assertField(Throwable e, String field, String code) {
        assertThat(e).isInstanceOf(ApiException.class);
        ApiException ex = (ApiException) e;
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(ex.getFieldErrors()).anySatisfy(f -> {
            assertThat(f.field()).isEqualTo(field);
            assertThat(f.reasonCode()).isEqualTo(code);
        });
    }

    private void nothingWritten() {
        verify(reportMapper, never()).revise(anyLong(), anyLong(), any(), any());
        verify(observationMapper, never()).insert(any());
        verify(observationMapper, never()).retireFormal(anyLong(), anyLong(), any(), anyLong(), any());
        verify(revisionMapper, never()).insert(any());
    }

    // ---------------------------------------------------------------- 访问与状态

    @Test
    void othersReportIsNotFoundAndNothingWritten() {
        willThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND)).given(access).requireOwnedReport(7L, 88L);
        assertThatThrownBy(() -> service.create(7L, 88L, cmd(1L, add(in("血红蛋白", "140")))))
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        nothingWritten();
    }

    @Test
    void listRevisionsOfOthersReportIsNotFound() {
        willThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND)).given(access).requireOwnedReport(7L, 88L);
        assertThatThrownBy(() -> service.listRevisions(7L, 88L))
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        verify(revisionMapper, never()).findByReport(anyLong());
    }

    @Test
    void nullBodyIsMalformed() {
        confirmed(3L);
        assertThatThrownBy(() -> service.create(7L, 21L, null))
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.MALFORMED_REQUEST);
    }

    @Test
    void unconfirmedReportCannotBeRevised() {
        Report r = confirmed(3L);
        r.setProcessingStatus(ProcessingStatus.PENDING_CONFIRMATION);
        formalRows();
        assertThatThrownBy(() -> service.create(7L, 21L, cmd(3L, add(in("血红蛋白", "140")))))
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.REPORT_STATE_CONFLICT);
        nothingWritten();
    }

    @Test
    void staleVersionConflicts() {
        confirmed(3L);
        formalRows(formal(1L, "血红蛋白", "140"));
        assertThatThrownBy(() -> service.create(7L, 21L, cmd(2L, update(1L, in("血红蛋白", "150")))))
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT);
        nothingWritten();
    }

    @Test
    void lostOptimisticLockConflicts() {
        confirmed(3L);
        formalRows(formal(1L, "血红蛋白", "140"));
        given(reportMapper.revise(anyLong(), anyLong(), any(), any())).willReturn(0);
        assertThatThrownBy(() -> service.create(7L, 21L, cmd(3L, update(1L, in("血红蛋白", "150")))))
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT);
        verify(revisionMapper, never()).insert(any());
    }

    @Test
    void concurrentRevisionMapsToVersionConflict() {
        confirmed(3L);
        formalRows(formal(1L, "血红蛋白", "140"));
        writesSucceed();
        willThrow(new DuplicateKeyException("uk")).given(revisionMapper).insert(any());
        assertThatThrownBy(() -> service.create(7L, 21L, cmd(3L, update(1L, in("血红蛋白", "150")))))
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT);
        verify(auditService, never()).success(anyLong(), any(), any());
    }

    @Test
    void rowRetiredConcurrentlyConflicts() {
        confirmed(3L);
        formalRows(formal(1L, "血红蛋白", "140"), formal(2L, "白细胞", "6.1"));
        writesSucceed();
        given(observationMapper.retireFormal(eq(21L), eq(2L), any(), anyLong(), any())).willReturn(0);
        assertThatThrownBy(() -> service.create(7L, 21L, cmd(3L, delete(2L))))
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT);
        verify(auditService, never()).success(anyLong(), any(), any());
    }

    // ---------------------------------------------------------------- 校验

    @Test
    void missingVersionIsRequired() {
        confirmed(3L);
        assertThatThrownBy(() -> service.create(7L, 21L,
                new CreateRevisionCommand(null, null, null, List.of(delete(1L)))))
                .satisfies(e -> assertField(e, "version", "REQUIRED"));
    }

    @Test
    void operationShapeErrorsUseIndexedPaths() {
        confirmed(3L);
        assertThatThrownBy(() -> service.create(7L, 21L, cmd(3L,
                new RevisionOperation(null, 1L, null),
                new RevisionOperation(OperationType.UPDATE, null, in("血红蛋白", "150")),
                new RevisionOperation(OperationType.ADD, null, null),
                add(in(" ", "150")))))
                .satisfies(e -> {
                    assertField(e, "operations[0].op", "REQUIRED");
                    assertField(e, "operations[1].observationId", "REQUIRED");
                    assertField(e, "operations[2].observation", "REQUIRED");
                    assertField(e, "operations[3].observation.originalName", "REQUIRED");
                });
        nothingWritten();
    }

    @Test
    void tooLongReasonIsRejected() {
        confirmed(3L);
        String reason = "改".repeat(ReportRevisionService.REASON_MAX + 1);
        assertThatThrownBy(() -> service.create(7L, 21L,
                new CreateRevisionCommand(3L, reason, null, List.of(delete(1L)))))
                .satisfies(e -> assertField(e, "reason", "TOO_LONG"));
    }

    @Test
    void reasonOfExactlyMaxCodePointsIsAccepted() {
        confirmed(3L);
        formalRows(formal(1L, "血红蛋白", "140"), formal(2L, "白细胞", "6.1"));
        writesSucceed();
        String reason = "𠀀".repeat(ReportRevisionService.REASON_MAX); // 补充平面字符，按码点计
        assertThat(service.create(7L, 21L, new CreateRevisionCommand(3L, reason, null, List.of(delete(2L)))))
                .isNotNull();
    }

    @Test
    void futureDateIsRejected() {
        confirmed(3L);
        assertThatThrownBy(() -> service.create(7L, 21L,
                new CreateRevisionCommand(3L, null, LocalDate.of(2026, 11, 5), List.of())))
                .satisfies(e -> assertField(e, "examinationDate", "FUTURE"));
    }

    @Test
    void unknownOrNonFormalRowIdIsRejected() {
        confirmed(3L);
        formalRows(formal(1L, "血红蛋白", "140"));
        assertThatThrownBy(() -> service.create(7L, 21L, cmd(3L, update(555L, in("血红蛋白", "150")))))
                .satisfies(e -> assertField(e, "operations[0].observationId", "UNKNOWN"));
        nothingWritten();
    }

    @Test
    void duplicateRowIdIsRejected() {
        confirmed(3L);
        formalRows(formal(1L, "血红蛋白", "140"), formal(2L, "白细胞", "6.1"));
        assertThatThrownBy(() -> service.create(7L, 21L,
                cmd(3L, update(1L, in("血红蛋白", "150")), delete(1L))))
                .satisfies(e -> assertField(e, "operations[1].observationId", "DUPLICATE"));
        nothingWritten();
    }

    @Test
    void updateWithoutChangeIsRejected() {
        confirmed(3L);
        formalRows(formal(1L, "血红蛋白", "140"));
        assertThatThrownBy(() -> service.create(7L, 21L, cmd(3L, update(1L, in(" 血红蛋白 ", "140")))))
                .satisfies(e -> assertField(e, "operations[0].observation", "NO_CHANGE"));
        nothingWritten();
    }

    @Test
    void foreignSourcePhotoIsRejected() {
        confirmed(3L);
        formalRows(formal(1L, "血红蛋白", "140"));
        assertThatThrownBy(() -> service.create(7L, 21L, cmd(3L,
                add(new ObservationInput(null, "血小板", "210", null, null, 777L)))))
                .satisfies(e -> assertField(e, "operations[0].observation.sourcePhotoId", "UNKNOWN"));
        nothingWritten();
    }

    @Test
    void deletingEveryRowIsRejected() {
        confirmed(3L);
        formalRows(formal(1L, "血红蛋白", "140"), formal(2L, "白细胞", "6.1"));
        assertThatThrownBy(() -> service.create(7L, 21L, cmd(3L, delete(1L), delete(2L))))
                .satisfies(e -> assertField(e, "operations", "LAST_OBSERVATION"));
        nothingWritten();
    }

    @Test
    void emptyOperationsWithoutDateChangeIsNoChange() {
        confirmed(3L);
        formalRows(formal(1L, "血红蛋白", "140"));
        assertThatThrownBy(() -> service.create(7L, 21L, new CreateRevisionCommand(3L, null, DATE, List.of())))
                .satisfies(e -> assertField(e, "operations", "NO_CHANGE"));
        nothingWritten();
    }

    // ---------------------------------------------------------------- 成功路径

    @Test
    void reviseKeepsOldRowsAndWritesRevisionAndAudit() {
        confirmed(3L);
        formalRows(formal(1L, "血红蛋白", "140"), formal(2L, "白细胞", "6.1"));
        writesSucceed();

        ReviseResult result = service.create(7L, 21L, new CreateRevisionCommand(3L, " 录入错误 ", null, List.of(
                update(1L, in("血红蛋白", "150")),
                add(new ObservationInput(null, "血小板", "210", null, null, 6L)),
                delete(2L))));

        assertThat(result.reportId()).isEqualTo(21L);
        assertThat(result.version()).isEqualTo(4L);
        assertThat(result.examinationDate()).isEqualTo(DATE);
        assertThat(result.revisionId()).isEqualTo(99L);
        assertThat(result.changeCount()).isEqualTo(3);

        // 新行：UPDATE 替代行沿用旧行顺序与来源照片，指向旧行；ADD 追加在末尾
        assertThat(inserted).hasSize(2);
        Observation replacement = inserted.get(0);
        assertThat(replacement.getId()).isEqualTo(100L);
        assertThat(replacement.getStatus()).isEqualTo(ObservationStatus.FORMAL);
        assertThat(replacement.getSupersedesId()).isEqualTo(1L);
        assertThat(replacement.getOriginalValue()).isEqualTo("150");
        assertThat(replacement.getRowOrder()).isZero();
        assertThat(replacement.getSourcePhotoId()).isEqualTo(5L);
        assertThat(replacement.getConfidence()).isNull();
        assertThat(replacement.getConfirmedAt()).isEqualTo(NOW);
        Observation added = inserted.get(1);
        assertThat(added.getSupersedesId()).isNull();
        assertThat(added.getRowOrder()).isEqualTo(2);
        assertThat(added.getSourcePhotoId()).isEqualTo(6L);
        assertThat(added.getExaminationDate()).isEqualTo(DATE);

        // 旧行只做状态失效，不更新、不删除
        verify(observationMapper).retireFormal(21L, 1L, ObservationStatus.SUPERSEDED, 99L, NOW);
        verify(observationMapper).retireFormal(21L, 2L, ObservationStatus.RETRACTED, 99L, NOW);
        verify(observationMapper).assignCreatedRevision(21L, List.of(100L, 101L), 99L);
        verify(observationMapper, never()).updateFormalExaminationDate(anyLong(), any(), any());
        verify(observationMapper, never()).deleteByReport(anyLong());
        verify(observationMapper).assignMetricDefinitions(21L);
        verify(reportMapper).revise(21L, 3L, DATE, NOW);
        verify(auditService).success(7L, AuditAction.REPORT_REVISE, "report:21");

        ArgumentCaptor<ReportRevision> captor = ArgumentCaptor.forClass(ReportRevision.class);
        verify(revisionMapper).insert(captor.capture());
        ReportRevision saved = captor.getValue();
        assertThat(saved.getFromVersion()).isEqualTo(3L);
        assertThat(saved.getToVersion()).isEqualTo(4L);
        assertThat(saved.getReason()).isEqualTo("录入错误");
        assertThat(saved.getActorAccountId()).isEqualTo(7L);
        // 差异正文加密存储：密文中不得出现明文旧值
        assertThat(new String(saved.getChangePayload(), StandardCharsets.UTF_8)).doesNotContain("血红蛋白");

        // 读回：解密后可还原修改前后，带新旧行 id
        given(revisionMapper.findByReport(21L)).willReturn(List.of(saved));
        List<RevisionView> views = service.listRevisions(7L, 21L);
        assertThat(views).hasSize(1);
        assertThat(views.getFirst().examinationDate()).isNull();
        List<RevisionChange> changes = views.getFirst().changes();
        assertThat(changes).extracting(RevisionChange::type)
                .containsExactly(ChangeType.UPDATED, ChangeType.ADDED, ChangeType.REMOVED);
        assertThat(changes.get(0).observationId()).isEqualTo(1L);
        assertThat(changes.get(0).newObservationId()).isEqualTo(100L);
        assertThat(changes.get(0).before().originalValue()).isEqualTo("140");
        assertThat(changes.get(0).after().originalValue()).isEqualTo("150");
        assertThat(changes.get(1).observationId()).isNull();
        assertThat(changes.get(1).newObservationId()).isEqualTo(101L);
        assertThat(changes.get(1).before()).isNull();
        assertThat(changes.get(2).observationId()).isEqualTo(2L);
        assertThat(changes.get(2).newObservationId()).isNull();
        assertThat(changes.get(2).after()).isNull();
    }

    @Test
    void dateOnlyChangeIsRecordedWithoutRowChanges() {
        confirmed(3L);
        formalRows(formal(1L, "血红蛋白", "140"));
        writesSucceed();
        LocalDate newDate = DATE.minusDays(1);

        ReviseResult result = service.create(7L, 21L, new CreateRevisionCommand(3L, null, newDate, List.of()));

        assertThat(result.changeCount()).isEqualTo(1);
        assertThat(result.examinationDate()).isEqualTo(newDate);
        verify(reportMapper).revise(21L, 3L, newDate, NOW);
        verify(observationMapper).updateFormalExaminationDate(21L, newDate, NOW);
        verify(observationMapper, never()).insert(any());
        verify(observationMapper, never()).retireFormal(anyLong(), anyLong(), any(), anyLong(), any());
        verify(observationMapper, never()).assignCreatedRevision(anyLong(), any(), anyLong());

        ArgumentCaptor<ReportRevision> captor = ArgumentCaptor.forClass(ReportRevision.class);
        verify(revisionMapper).insert(captor.capture());
        given(revisionMapper.findByReport(21L)).willReturn(List.of(captor.getValue()));
        RevisionView view = service.listRevisions(7L, 21L).getFirst();
        assertThat(view.examinationDate().before()).isEqualTo("2026-10-20");
        assertThat(view.examinationDate().after()).isEqualTo("2026-10-19");
        assertThat(view.changes()).isEmpty();
    }

    @Test
    void addedRowsAppendAfterCurrentMaxRowOrder() {
        confirmed(3L);
        formalRows(formal(1L, "血红蛋白", "140"));
        writesSucceed();
        given(observationMapper.maxRowOrder(21L)).willReturn(7);

        service.create(7L, 21L, cmd(3L, add(in("血小板", "210")), add(in("红细胞", "4.5"))));

        assertThat(inserted).extracting(Observation::getRowOrder).containsExactly(8, 9);
        verify(observationMapper, never()).retireFormal(anyLong(), anyLong(), any(), anyLong(), any());
    }

    // ---------------------------------------------------------------- 单项版本链

    private static Observation version(long id, Long supersedes, ObservationStatus status, String value) {
        Observation o = formal(id, "血红蛋白", value);
        o.setStatus(status);
        o.setSupersedesId(supersedes);
        return o;
    }

    @Test
    void historyWalksChainNewestFirstFromAnyVersion() {
        confirmed(5L);
        given(observationMapper.findAllByReport(21L)).willReturn(List.of(
                version(1L, null, ObservationStatus.SUPERSEDED, "140"),
                version(2L, null, ObservationStatus.FORMAL, "6.1"),
                version(3L, 1L, ObservationStatus.SUPERSEDED, "150"),
                version(4L, 3L, ObservationStatus.RETRACTED, "155")));

        for (long start : new long[] {1L, 3L, 4L}) {
            assertThat(service.observationHistory(7L, 21L, start))
                    .extracting(ObservationVersionView::id).containsExactly(4L, 3L, 1L);
        }
        assertThat(service.observationHistory(7L, 21L, 4L)).extracting(ObservationVersionView::status)
                .containsExactly(ObservationStatus.RETRACTED, ObservationStatus.SUPERSEDED,
                        ObservationStatus.SUPERSEDED);
        assertThat(service.observationHistory(7L, 21L, 2L)).extracting(ObservationVersionView::id)
                .containsExactly(2L);
    }

    @Test
    void historyOfDraftOrUnknownRowIsNotFound() {
        confirmed(5L);
        given(observationMapper.findAllByReport(21L)).willReturn(List.of(
                version(1L, null, ObservationStatus.DRAFT, "140")));
        assertThatThrownBy(() -> service.observationHistory(7L, 21L, 1L))
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThatThrownBy(() -> service.observationHistory(7L, 21L, 9L))
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }
}
