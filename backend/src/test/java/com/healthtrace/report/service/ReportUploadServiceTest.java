package com.healthtrace.report.service;

import com.healthtrace.account.domain.Account;
import com.healthtrace.account.mapper.AccountMapper;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.profile.domain.ProfileRecord;
import com.healthtrace.profile.mapper.ProfileMapper;
import com.healthtrace.report.domain.DuplicateDecision;
import com.healthtrace.report.domain.Photo;
import com.healthtrace.report.domain.ProcessingStatus;
import com.healthtrace.report.domain.Report;
import com.healthtrace.report.domain.ReportListFilter;
import com.healthtrace.report.domain.ReportPolicy;
import com.healthtrace.report.domain.StorageStatus;
import com.healthtrace.report.domain.VisibilityStatus;
import com.healthtrace.report.mapper.PhotoMapper;
import com.healthtrace.report.mapper.ReportMapper;
import com.healthtrace.report.service.ReportViews.PhotoUploadResult;
import com.healthtrace.report.service.ReportViews.PhotoVariant;
import com.healthtrace.report.service.ReportViews.UploadOutcome;
import com.healthtrace.storage.StorageException;
import com.healthtrace.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportUploadServiceTest {

    private static final long ACCOUNT = 7L;
    private static final long PROFILE = 11L;
    private static final long REPORT = 21L;

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-', '1', '.', '7', '\n', '%', 'a', 'b'};
    private static final byte[] HEIC = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'};
    private static final byte[] EXE = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0, 0, 0};

    private final ReportMapper reportMapper = mock(ReportMapper.class);
    private final PhotoMapper photoMapper = mock(PhotoMapper.class);
    private final ProfileMapper profileMapper = mock(ProfileMapper.class);
    private final AccountMapper accountMapper = mock(AccountMapper.class);
    private final StorageService storage = mock(StorageService.class);
    private final com.healthtrace.report.mapper.ObservationMapper observationMapper =
            mock(com.healthtrace.report.mapper.ObservationMapper.class);
    private final com.healthtrace.rights.audit.AuditService auditService =
            mock(com.healthtrace.rights.audit.AuditService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC);

    private ReportUploadService service;

    @BeforeEach
    void setUp() {
        service = new ReportUploadService(new ReportAccess(reportMapper, profileMapper, accountMapper), reportMapper,
                photoMapper, observationMapper, profileMapper, storage, auditService, clock);
        given(profileMapper.findActiveByAccountAndProfile(ACCOUNT, PROFILE)).willReturn(new ProfileRecord());
        given(accountMapper.findById(ACCOUNT)).willReturn(account(true));
        given(reportMapper.findById(REPORT)).willReturn(report(ProcessingStatus.UPLOADING));
        given(photoMapper.findReportIdsByDigest(anyLong(), anyString())).willReturn(List.of());
        doAnswer(inv -> {
            Photo photo = inv.getArgument(0);
            // 同一对象随后会被改为 COMPLETE，这里记录插入时刻的状态
            statusAtInsert = photo.getStorageStatus();
            photo.setId(99L);
            return 1;
        }).when(photoMapper).insert(any(Photo.class));
        doAnswer(inv -> {
            inv.<Report>getArgument(0).setId(REPORT);
            return 1;
        }).when(reportMapper).insert(any(Report.class));
    }

    private StorageStatus statusAtInsert;

    private static Account account(boolean verified) {
        Account account = new Account();
        account.setId(ACCOUNT);
        account.setEmailVerifiedAt(verified ? LocalDateTime.of(2026, 10, 1, 0, 0) : null);
        return account;
    }

    private static Report report(ProcessingStatus status) {
        Report report = new Report();
        report.setId(REPORT);
        report.setProfileId(PROFILE);
        report.setCreatedBy(ACCOUNT);
        report.setProcessingStatus(status);
        report.setVisibilityStatus(VisibilityStatus.NORMAL);
        return report;
    }

    private static MockMultipartFile file(String part, byte[] content) {
        return new MockMultipartFile(part, "x.bin", "application/octet-stream", content);
    }

    private static ErrorCode codeOf(Throwable t) {
        return ((ApiException) t).getErrorCode();
    }

    private PhotoUploadResult upload(MockMultipartFile original, MockMultipartFile preview, DuplicateDecision d) {
        return service.uploadPhoto(ACCOUNT, REPORT, original, preview, 1, d);
    }

    // ------------------------------------------------------------ create / list

    @Test
    void createReportStartsUploadingWithUnknownType() {
        var view = service.createReport(ACCOUNT, PROFILE, "  血常规  ", LocalDate.of(2026, 10, 20), null, null);

        ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
        verify(reportMapper).insert(captor.capture());
        Report saved = captor.getValue();
        assertThat(saved.getCreatedBy()).isEqualTo(ACCOUNT);
        assertThat(saved.getProcessingStatus()).isEqualTo(ProcessingStatus.UPLOADING);
        assertThat(saved.getVisibilityStatus()).isEqualTo(VisibilityStatus.NORMAL);
        assertThat(saved.getTitle()).isEqualTo("血常规");
        assertThat(view.processingStatus()).isEqualTo(ProcessingStatus.UPLOADING);
    }

    @Test
    void createReportOnOthersProfileIsNotFound() {
        assertThatThrownBy(() -> service.createReport(ACCOUNT, 999L, null, null, null, null))
                .satisfies(e -> assertThat(codeOf(e).code()).isEqualTo("HT-3001"));
        verify(reportMapper, never()).insert(any());
    }

    @Test
    void createReportRequiresVerifiedEmail() {
        given(accountMapper.findById(ACCOUNT)).willReturn(account(false));

        assertThatThrownBy(() -> service.createReport(ACCOUNT, PROFILE, null, null, null, null))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED));
    }

    @Test
    void createReportRejectsFutureExaminationDate() {
        assertThatThrownBy(() -> service.createReport(ACCOUNT, PROFILE, null, LocalDate.of(2026, 10, 28), null, null))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void createReportRejectsOverlongTitle() {
        String title = "报".repeat(ReportPolicy.TITLE_MAX_LENGTH + 1);
        assertThatThrownBy(() -> service.createReport(ACCOUNT, PROFILE, title, null, null, null))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void createReportDefaultsToStructuredKind() {
        service.createReport(ACCOUNT, PROFILE, null, null, null, null);

        ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
        verify(reportMapper).insert(captor.capture());
        assertThat(captor.getValue().getReportKind()).isEqualTo(com.healthtrace.report.domain.ReportKind.STRUCTURED);
        assertThat(captor.getValue().getConclusionText()).isNull();
    }

    @Test
    void createImagingReportKeepsTrimmedConclusion() {
        var view = service.createReport(ACCOUNT, PROFILE, "腹部超声", null, "imaging", "  肝胆胰脾未见明显异常  ");

        ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
        verify(reportMapper).insert(captor.capture());
        assertThat(captor.getValue().getReportKind()).isEqualTo(com.healthtrace.report.domain.ReportKind.IMAGING);
        assertThat(captor.getValue().getConclusionText()).isEqualTo("肝胆胰脾未见明显异常");
        assertThat(view.reportKind()).isEqualTo(com.healthtrace.report.domain.ReportKind.IMAGING);
        assertThat(view.conclusionText()).isEqualTo("肝胆胰脾未见明显异常");
    }

    @Test
    void createReportRejectsUnknownKind() {
        assertThatThrownBy(() -> service.createReport(ACCOUNT, PROFILE, null, null, "XRAY", null))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(reportMapper, never()).insert(any());
    }

    @Test
    void createStructuredReportRejectsConclusion() {
        assertThatThrownBy(() -> service.createReport(ACCOUNT, PROFILE, null, null, "STRUCTURED", "正常"))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(reportMapper, never()).insert(any());
    }

    @Test
    void createImagingReportRejectsOverlongConclusion() {
        String text = "影".repeat(ReportPolicy.CONCLUSION_MAX_LENGTH + 1);
        assertThatThrownBy(() -> service.createReport(ACCOUNT, PROFILE, null, null, "IMAGING", text))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    // ------------------------------------------------------------ completeImaging

    private Report imagingReport(ProcessingStatus status) {
        Report r = report(status);
        r.setReportKind(com.healthtrace.report.domain.ReportKind.IMAGING);
        return r;
    }

    @Test
    void completeImagingConfirmsWhenFileUploaded() {
        given(reportMapper.findById(REPORT)).willReturn(imagingReport(ProcessingStatus.UPLOADING));
        given(photoMapper.findCompleteByReport(REPORT)).willReturn(List.of(new Photo()));
        given(reportMapper.completeWithoutObservations(eq(REPORT), eq(ACCOUNT), any())).willReturn(1);

        var view = service.completeImaging(ACCOUNT, REPORT);

        assertThat(view.processingStatus()).isEqualTo(ProcessingStatus.CONFIRMED);
    }

    @Test
    void completeImagingRejectsStructuredReport() {
        Report structured = report(ProcessingStatus.UPLOADING);
        structured.setReportKind(com.healthtrace.report.domain.ReportKind.STRUCTURED);
        given(reportMapper.findById(REPORT)).willReturn(structured);

        assertThatThrownBy(() -> service.completeImaging(ACCOUNT, REPORT))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.REPORT_STATE_CONFLICT));
        verify(reportMapper, never()).completeWithoutObservations(anyLong(), anyLong(), any());
    }

    @Test
    void completeImagingRejectsWithoutFiles() {
        given(reportMapper.findById(REPORT)).willReturn(imagingReport(ProcessingStatus.UPLOADING));
        given(photoMapper.findCompleteByReport(REPORT)).willReturn(List.of());

        assertThatThrownBy(() -> service.completeImaging(ACCOUNT, REPORT))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.REPORT_STATE_CONFLICT));
    }

    @Test
    void completeImagingHidesForeignReport() {
        Report foreign = imagingReport(ProcessingStatus.UPLOADING);
        foreign.setProfileId(999L);
        given(reportMapper.findById(REPORT)).willReturn(foreign);
        given(profileMapper.findActiveByAccountAndProfile(ACCOUNT, 999L)).willReturn(null);

        assertThatThrownBy(() -> service.completeImaging(ACCOUNT, REPORT))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // ------------------------------------------------------------ upload

    @Test
    void uploadJpegWithPreviewStoresBothAndCompletes() {
        PhotoUploadResult result = upload(file("original", JPEG), file("preview", JPEG), DuplicateDecision.ASK);

        assertThat(result.result()).isEqualTo(UploadOutcome.UPLOADED);
        assertThat(result.photo().hasPreview()).isTrue();
        ArgumentCaptor<Photo> captor = ArgumentCaptor.forClass(Photo.class);
        verify(photoMapper).insert(captor.capture());
        Photo saved = captor.getValue();
        assertThat(statusAtInsert).isEqualTo(StorageStatus.PENDING);
        assertThat(saved.getMediaType()).isEqualTo("image/jpeg");
        assertThat(saved.getObjectKey()).startsWith("profiles/" + PROFILE + "/reports/" + REPORT + "/");
        assertThat(saved.getContentDigest()).hasSize(64);
        verify(storage).put(eq(saved.getObjectKey()), any(InputStream.class), eq((long) JPEG.length), eq("image/jpeg"));
        verify(storage).put(eq(saved.getPreviewObjectKey()), any(InputStream.class), anyLong(), eq("image/jpeg"));
        verify(photoMapper).updateStorageStatus(eq(99L), eq(StorageStatus.COMPLETE), any());
    }

    @Test
    void uploadDetectsTypeByMagicNumberNotDeclaredContentType() {
        MockMultipartFile disguised = new MockMultipartFile("original", "a.jpg", "image/jpeg", EXE);

        assertThatThrownBy(() -> upload(disguised, null, DuplicateDecision.ASK))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
        verify(photoMapper, never()).insert(any());
    }

    @Test
    void heicAndPdfAreStoredOriginalOnly() {
        assertThat(upload(file("original", HEIC), null, DuplicateDecision.ASK).photo().mediaType())
                .isEqualTo("image/heic");
        assertThat(upload(file("original", PDF), null, DuplicateDecision.ASK).photo().mediaType())
                .isEqualTo("application/pdf");
    }

    @Test
    void previewNotAllowedForPdf() {
        assertThatThrownBy(() -> upload(file("original", PDF), file("preview", JPEG), DuplicateDecision.ASK))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void previewMustBeJpegOrPng() {
        assertThatThrownBy(() -> upload(file("original", PNG), file("preview", PDF), DuplicateDecision.ASK))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
    }

    @Test
    void oversizedFileRejected() {
        MockMultipartFile big = mock(MockMultipartFile.class);
        when(big.isEmpty()).thenReturn(false);
        when(big.getSize()).thenReturn(ReportPolicy.MAX_FILE_BYTES + 1);

        assertThatThrownBy(() -> upload(big, null, DuplicateDecision.ASK))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.FILE_TOO_LARGE));
    }

    @Test
    void twentyFirstFileRejected() {
        given(photoMapper.countActiveByReport(REPORT)).willReturn(ReportPolicy.MAX_FILES_PER_REPORT);

        assertThatThrownBy(() -> upload(file("original", JPEG), null, DuplicateDecision.ASK))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.REPORT_FILE_LIMIT));
    }

    @Test
    void accountQuotaEnforced() {
        given(photoMapper.sumActiveBytesByAccount(ACCOUNT)).willReturn(ReportPolicy.MAX_BYTES_PER_ACCOUNT - 1);

        assertThatThrownBy(() -> upload(file("original", JPEG), null, DuplicateDecision.ASK))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.STORAGE_QUOTA_EXCEEDED));
    }

    @Test
    void pageOrderOutOfRangeRejected() {
        assertThatThrownBy(() -> service.uploadPhoto(ACCOUNT, REPORT, file("original", JPEG), null, 21,
                DuplicateDecision.ASK))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void uploadRequiresVerifiedEmail() {
        given(accountMapper.findById(ACCOUNT)).willReturn(account(false));

        assertThatThrownBy(() -> upload(file("original", JPEG), null, DuplicateDecision.ASK))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED));
    }

    @Test
    void uploadToOthersReportIsNotFound() {
        Report foreign = report(ProcessingStatus.UPLOADING);
        foreign.setProfileId(999L);
        given(reportMapper.findById(REPORT)).willReturn(foreign);

        assertThatThrownBy(() -> upload(file("original", JPEG), null, DuplicateDecision.ASK))
                .satisfies(e -> assertThat(codeOf(e).code()).isEqualTo("HT-3001"));
        verify(storage, never()).put(anyString(), any(), anyLong(), anyString());
    }

    @Test
    void uploadRejectedAfterReportLeftUploading() {
        given(reportMapper.findById(REPORT)).willReturn(report(ProcessingStatus.RECOGNIZING));

        assertThatThrownBy(() -> upload(file("original", JPEG), null, DuplicateDecision.ASK))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.REPORT_STATE_CONFLICT));
    }

    @Test
    void duplicateWithAskIsNotStored() {
        given(photoMapper.findReportIdsByDigest(eq(PROFILE), anyString())).willReturn(List.of(5L, 8L));

        PhotoUploadResult result = upload(file("original", JPEG), null, DuplicateDecision.ASK);

        assertThat(result.result()).isEqualTo(UploadOutcome.DUPLICATE_SUSPECTED);
        assertThat(result.existingReportIds()).containsExactly(5L, 8L);
        verify(photoMapper, never()).insert(any());
        verify(storage, never()).put(anyString(), any(), anyLong(), anyString());
    }

    @Test
    void duplicateWithKeepIsStored() {
        given(photoMapper.findReportIdsByDigest(eq(PROFILE), anyString())).willReturn(List.of(5L));

        PhotoUploadResult result = upload(file("original", JPEG), null, DuplicateDecision.KEEP);

        assertThat(result.result()).isEqualTo(UploadOutcome.UPLOADED);
        verify(photoMapper).insert(any());
    }

    @Test
    void storageFailureMarksFailedAndCleansUp() {
        given(storage.put(anyString(), any(), anyLong(), anyString())).willThrow(new StorageException("boom"));

        assertThatThrownBy(() -> upload(file("original", JPEG), file("preview", JPEG), DuplicateDecision.ASK))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.INTERNAL_ERROR));

        ArgumentCaptor<Photo> captor = ArgumentCaptor.forClass(Photo.class);
        verify(photoMapper).insert(captor.capture());
        verify(storage).delete(captor.getValue().getObjectKey());
        verify(storage).delete(captor.getValue().getPreviewObjectKey());
        verify(photoMapper).updateStorageStatus(eq(99L), eq(StorageStatus.FAILED), any());
        verify(photoMapper, never()).updateStorageStatus(eq(99L), eq(StorageStatus.COMPLETE), any());
    }

    @Test
    void concurrentSamePageIsConflict() {
        doAnswer(inv -> {
            throw new DuplicateKeyException("uk_photo_report_page");
        }).when(photoMapper).insert(any(Photo.class));

        assertThatThrownBy(() -> upload(file("original", JPEG), null, DuplicateDecision.ASK))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.REPORT_STATE_CONFLICT));
        verify(storage, never()).put(anyString(), any(), anyLong(), anyString());
    }

    // ------------------------------------------------------------ metadata update

    private Report confirmedStructured(LocalDate date) {
        Report report = report(ProcessingStatus.CONFIRMED);
        report.setReportKind(com.healthtrace.report.domain.ReportKind.STRUCTURED);
        report.setExaminationDate(date);
        report.setTitle("旧标题");
        report.setVersion(4L);
        given(reportMapper.findById(REPORT)).willReturn(report);
        return report;
    }

    @Test
    void updateMetadataWritesFieldsBumpsVersionAndAudits() {
        confirmedStructured(LocalDate.of(2026, 10, 1));
        given(reportMapper.updateMetadata(eq(REPORT), eq(4L), any(), any(), any())).willReturn(1);

        var view = service.updateMetadata(ACCOUNT, REPORT, "  新标题  ", LocalDate.of(2026, 10, 20));

        LocalDateTime now = LocalDateTime.of(2026, 10, 25, 10, 0);
        verify(reportMapper).updateMetadata(REPORT, 4L, "新标题", LocalDate.of(2026, 10, 20), now);
        verify(observationMapper).updateFormalExaminationDate(REPORT, LocalDate.of(2026, 10, 20), now);
        verify(auditService).success(ACCOUNT, com.healthtrace.rights.audit.AuditAction.REPORT_METADATA_UPDATE,
                com.healthtrace.rights.audit.AuditService.token("report", REPORT));
        assertThat(view.title()).isEqualTo("新标题");
        assertThat(view.examinationDate()).isEqualTo(LocalDate.of(2026, 10, 20));
    }

    @Test
    void updateMetadataBlankTitleBecomesNullAndSameDateSkipsObservations() {
        confirmedStructured(LocalDate.of(2026, 10, 1));
        given(reportMapper.updateMetadata(eq(REPORT), eq(4L), any(), any(), any())).willReturn(1);

        var view = service.updateMetadata(ACCOUNT, REPORT, "   ", LocalDate.of(2026, 10, 1));

        verify(reportMapper).updateMetadata(eq(REPORT), eq(4L), eq(null), eq(LocalDate.of(2026, 10, 1)), any());
        verify(observationMapper, never()).updateFormalExaminationDate(anyLong(), any(), any());
        assertThat(view.title()).isNull();
    }

    @Test
    void updateMetadataOnOthersReportIsNotFound() {
        Report report = confirmedStructured(LocalDate.of(2026, 10, 1));
        report.setProfileId(999L);

        assertThatThrownBy(() -> service.updateMetadata(ACCOUNT, REPORT, "x", null))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        verify(reportMapper, never()).updateMetadata(anyLong(), anyLong(), any(), any(), any());
        verify(auditService, never()).success(anyLong(), any(), any());
    }

    @Test
    void updateMetadataOnRecycledReportIsNotFound() {
        Report report = confirmedStructured(LocalDate.of(2026, 10, 1));
        report.setVisibilityStatus(VisibilityStatus.LIST_REMOVED);

        assertThatThrownBy(() -> service.updateMetadata(ACCOUNT, REPORT, "x", null))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        verify(reportMapper, never()).updateMetadata(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void updateMetadataFutureDateIsRejected() {
        confirmedStructured(LocalDate.of(2026, 10, 1));

        assertThatThrownBy(() -> service.updateMetadata(ACCOUNT, REPORT, null, LocalDate.of(2026, 10, 27)))
                .satisfies(e -> {
                    assertThat(codeOf(e)).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(((ApiException) e).getFieldErrors()).extracting("field").containsExactly("examinationDate");
                });
        verify(reportMapper, never()).updateMetadata(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void updateMetadataOverlongTitleIsRejected() {
        confirmedStructured(LocalDate.of(2026, 10, 1));
        String title = "检".repeat(ReportPolicy.TITLE_MAX_LENGTH + 1);

        assertThatThrownBy(() -> service.updateMetadata(ACCOUNT, REPORT, title, null))
                .satisfies(e -> {
                    assertThat(codeOf(e)).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(((ApiException) e).getFieldErrors()).extracting("field").containsExactly("title");
                });
        verify(reportMapper, never()).updateMetadata(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void updateMetadataClearingDateOfConfirmedStructuredIsRejected() {
        confirmedStructured(LocalDate.of(2026, 10, 1));

        assertThatThrownBy(() -> service.updateMetadata(ACCOUNT, REPORT, "x", null))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void updateMetadataVersionRaceIsConflict() {
        confirmedStructured(LocalDate.of(2026, 10, 1));
        given(reportMapper.updateMetadata(anyLong(), anyLong(), any(), any(), any())).willReturn(0);

        assertThatThrownBy(() -> service.updateMetadata(ACCOUNT, REPORT, "x", LocalDate.of(2026, 10, 2)))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.VERSION_CONFLICT));
        verify(auditService, never()).success(anyLong(), any(), any());
        verify(observationMapper, never()).updateFormalExaminationDate(anyLong(), any(), any());
    }

    // ------------------------------------------------------------ original filename

    @Test
    void sanitizeFilenameStripsPathsControlsAndBlank() {
        assertThat(ReportUploadService.sanitizeFilename(null)).isNull();
        assertThat(ReportUploadService.sanitizeFilename("   ")).isNull();
        assertThat(ReportUploadService.sanitizeFilename("C:\\Users\\me\\血常规.jpg")).isEqualTo("血常规.jpg");
        assertThat(ReportUploadService.sanitizeFilename("../../etc/passwd")).isEqualTo("passwd");
        assertThat(ReportUploadService.sanitizeFilename("dir/")).isNull();
        assertThat(ReportUploadService.sanitizeFilename(" a\u0000b\r\n\u202Ec.pdf ")).isEqualTo("abc.pdf");
    }

    @Test
    void sanitizeFilenameTruncatesByCodePoints() {
        String emoji = "\uD83D\uDE00"; // 一个码点、两个 char
        String name = emoji.repeat(300);
        String result = ReportUploadService.sanitizeFilename(name);
        assertThat(result.codePointCount(0, result.length())).isEqualTo(255);
        assertThat(result).isEqualTo(emoji.repeat(255));
    }

    @Test
    void uploadStoresSanitizedOriginalFilename() {
        MockMultipartFile original = new MockMultipartFile("original", "C:\\fakepath\\报告\u0007.jpg",
                "application/octet-stream", JPEG);

        upload(original, null, DuplicateDecision.ASK);

        ArgumentCaptor<Photo> captor = ArgumentCaptor.forClass(Photo.class);
        verify(photoMapper).insert(captor.capture());
        assertThat(captor.getValue().getOriginalFilename()).isEqualTo("报告.jpg");
        assertThat(captor.getValue().getMediaType()).isEqualTo("image/jpeg");
    }
}
