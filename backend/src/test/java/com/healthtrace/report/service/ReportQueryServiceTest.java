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

class ReportQueryServiceTest {

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
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC);

    private ReportQueryService service;

    @BeforeEach
    void setUp() {
        service = new ReportQueryService(new ReportAccess(reportMapper, profileMapper, accountMapper), reportMapper,
                photoMapper, profileMapper, storage);
        given(profileMapper.findActiveByAccountAndProfile(ACCOUNT, PROFILE)).willReturn(new ProfileRecord());
        given(reportMapper.findById(REPORT)).willReturn(report(ProcessingStatus.UPLOADING));
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

    private static ErrorCode codeOf(Throwable t) {
        return ((ApiException) t).getErrorCode();
    }

    @Test
    void listReportsChecksOwnership() {
        assertThatThrownBy(() -> service.listReports(ACCOUNT, 999L, ReportListFilter.DRAFT, null))
                .satisfies(e -> assertThat(codeOf(e).code()).isEqualTo("HT-3001"));
        verify(reportMapper, never()).findPendingByProfile(anyLong(), any());
    }

    @Test
    void listReportsWithoutLimitSkipsCountQuery() {
        com.healthtrace.report.domain.ReportSummary s = new com.healthtrace.report.domain.ReportSummary();
        s.setId(41L);
        s.setProcessingStatus(ProcessingStatus.PENDING_CONFIRMATION);
        given(reportMapper.findPendingByProfile(PROFILE, null)).willReturn(List.of(s));

        var page = service.listReports(ACCOUNT, PROFILE, ReportListFilter.DRAFT, null);

        assertThat(page.items()).extracting(i -> i.id()).containsExactly(41L);
        assertThat(page.total()).isEqualTo(1);
        verify(reportMapper, never()).countPendingByProfile(anyLong());
    }

    @Test
    void listReportsWithFullLimitUsesCountQuery() {
        com.healthtrace.report.domain.ReportSummary a = new com.healthtrace.report.domain.ReportSummary();
        a.setId(42L);
        com.healthtrace.report.domain.ReportSummary b = new com.healthtrace.report.domain.ReportSummary();
        b.setId(41L);
        given(reportMapper.findPendingByProfile(PROFILE, 2)).willReturn(List.of(a, b));
        given(reportMapper.countPendingByProfile(PROFILE)).willReturn(5L);

        var page = service.listReports(ACCOUNT, PROFILE, ReportListFilter.DRAFT, 2);

        assertThat(page.items()).extracting(i -> i.id()).containsExactly(42L, 41L);
        assertThat(page.total()).isEqualTo(5);
    }

    @Test
    void listReportsWithShortPageSkipsCountQuery() {
        com.healthtrace.report.domain.ReportSummary a = new com.healthtrace.report.domain.ReportSummary();
        a.setId(42L);
        given(reportMapper.findConfirmedByProfile(PROFILE, 10)).willReturn(List.of(a));

        var page = service.listReports(ACCOUNT, PROFILE, ReportListFilter.CONFIRMED, 10);

        assertThat(page.total()).isEqualTo(1);
        verify(reportMapper, never()).countConfirmedByProfile(anyLong());
    }

    @Test
    void listConfirmedReportsUsesConfirmedQuery() {
        com.healthtrace.report.domain.ReportSummary s = new com.healthtrace.report.domain.ReportSummary();
        s.setId(31L);
        s.setTitle("甲功");
        s.setProcessingStatus(ProcessingStatus.CONFIRMED);
        s.setExaminationDate(LocalDate.of(2026, 10, 1));
        given(reportMapper.findConfirmedByProfile(PROFILE, null)).willReturn(List.of(s));

        var page = service.listReports(ACCOUNT, PROFILE, ReportListFilter.CONFIRMED, null);

        assertThat(page.items()).singleElement().satisfies(i -> {
            assertThat(i.id()).isEqualTo(31L);
            assertThat(i.processingStatus()).isEqualTo(ProcessingStatus.CONFIRMED);
        });
        assertThat(page.total()).isEqualTo(1);
        verify(reportMapper, never()).findPendingByProfile(anyLong(), any());
    }

    @Test
    void listConfirmedReportsChecksOwnership() {
        assertThatThrownBy(() -> service.listReports(ACCOUNT, 999L, ReportListFilter.CONFIRMED, null))
                .satisfies(e -> assertThat(codeOf(e).code()).isEqualTo("HT-3001"));
        verify(reportMapper, never()).findConfirmedByProfile(anyLong(), any());
    }

    @Test
    void listConfirmedGroupsAggregatesByCleanedTitle() {
        com.healthtrace.report.domain.ReportSummary a = new com.healthtrace.report.domain.ReportSummary();
        a.setId(32L);
        a.setTitle("血常规 2026-10-01");
        a.setProcessingStatus(ProcessingStatus.CONFIRMED);
        a.setExaminationDate(LocalDate.of(2026, 10, 1));
        com.healthtrace.report.domain.ReportSummary b = new com.healthtrace.report.domain.ReportSummary();
        b.setId(31L);
        b.setTitle("血常规报告");
        b.setProcessingStatus(ProcessingStatus.CONFIRMED);
        b.setExaminationDate(LocalDate.of(2026, 5, 1));
        given(reportMapper.findConfirmedByProfile(PROFILE, null)).willReturn(List.of(a, b));

        var groups = service.listConfirmedGroups(ACCOUNT, PROFILE);

        assertThat(groups).singleElement().satisfies(g -> {
            assertThat(g.label()).isEqualTo("血常规");
            assertThat(g.count()).isEqualTo(2);
            assertThat(g.reports()).extracting(r -> r.id()).containsExactly(32L, 31L);
        });
    }

    @Test
    void listConfirmedGroupsChecksOwnership() {
        assertThatThrownBy(() -> service.listConfirmedGroups(ACCOUNT, 999L))
                .satisfies(e -> assertThat(codeOf(e).code()).isEqualTo("HT-3001"));
        verify(reportMapper, never()).findConfirmedByProfile(anyLong(), any());
    }

    // ------------------------------------------------------------ access

    private Photo completePhoto(boolean withPreview) {
        Photo photo = new Photo();
        photo.setId(99L);
        photo.setReportId(REPORT);
        photo.setProfileId(PROFILE);
        photo.setObjectKey("profiles/11/reports/21/a.jpg");
        photo.setMediaType("image/jpeg");
        photo.setByteSize(100);
        if (withPreview) {
            photo.setPreviewObjectKey("profiles/11/reports/21/a-preview.jpg");
            photo.setPreviewMediaType("image/jpeg");
            photo.setPreviewByteSize(10L);
        }
        photo.setStorageStatus(StorageStatus.COMPLETE);
        return photo;
    }

    @Test
    void photoWithoutPreviewIsNotFoundForPreviewVariant() {
        given(photoMapper.findById(99L)).willReturn(completePhoto(false));

        assertThatThrownBy(() -> service.openPhoto(ACCOUNT, 99L, PhotoVariant.PREVIEW))
                .satisfies(e -> assertThat(codeOf(e).code()).isEqualTo("HT-3001"));
    }

    @Test
    void othersPhotoIsNotFound() {
        Photo photo = completePhoto(true);
        given(photoMapper.findById(99L)).willReturn(photo);
        Report foreign = report(ProcessingStatus.UPLOADING);
        foreign.setProfileId(999L);
        given(reportMapper.findById(REPORT)).willReturn(foreign);

        assertThatThrownBy(() -> service.openPhoto(ACCOUNT, 99L, PhotoVariant.ORIGINAL))
                .satisfies(e -> assertThat(codeOf(e).code()).isEqualTo("HT-3001"));
        verify(storage, never()).open(anyString());
    }

    @Test
    void failedPhotoIsNotFound() {
        Photo photo = completePhoto(true);
        photo.setStorageStatus(StorageStatus.FAILED);
        given(photoMapper.findById(99L)).willReturn(photo);

        assertThatThrownBy(() -> service.openPhoto(ACCOUNT, 99L, PhotoVariant.ORIGINAL))
                .satisfies(e -> assertThat(codeOf(e).code()).isEqualTo("HT-3001"));
    }

    @Test
    void localStorageStreamsPreviewContent() {
        given(photoMapper.findById(99L)).willReturn(completePhoto(true));
        given(storage.createAccessUrl(anyString(), any())).willReturn(java.util.Optional.empty());
        InputStream stream = InputStream.nullInputStream();
        given(storage.open("profiles/11/reports/21/a-preview.jpg")).willReturn(stream);

        var content = service.openPhoto(ACCOUNT, 99L, PhotoVariant.PREVIEW);

        assertThat(content.redirect()).isNull();
        assertThat(content.content()).isSameAs(stream);
        assertThat(content.byteSize()).isEqualTo(10L);
    }
}
