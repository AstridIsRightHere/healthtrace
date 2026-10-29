package com.healthtrace.visit.service;

import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.profile.domain.ProfileRecord;
import com.healthtrace.profile.mapper.ProfileMapper;
import com.healthtrace.report.domain.Report;
import com.healthtrace.report.domain.VisibilityStatus;
import com.healthtrace.report.mapper.ReportMapper;
import com.healthtrace.visit.domain.Visit;
import com.healthtrace.visit.mapper.VisitMapper;
import com.healthtrace.visit.service.VisitViews.VisitInput;
import com.healthtrace.visit.service.VisitViews.VisitView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 就诊时间线服务（FR-013 / TC-013）：越权 404、跨档案关联 404、字段校验、乐观锁。 */
class VisitServiceTest {

    private static final long ACCOUNT = 7L;
    private static final long OTHER_ACCOUNT = 8L;
    private static final long PROFILE = 11L;
    private static final long OTHER_PROFILE = 12L;
    private static final long VISIT = 31L;
    private static final long REPORT = 21L;
    private static final long FOREIGN_REPORT = 22L;

    private final VisitMapper visitMapper = mock(VisitMapper.class);
    private final ReportMapper reportMapper = mock(ReportMapper.class);
    private final ProfileMapper profileMapper = mock(ProfileMapper.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-27T10:00:00Z"), ZoneOffset.UTC);

    private VisitService service;

    @BeforeEach
    void setUp() {
        service = new VisitService(visitMapper, reportMapper, profileMapper,
                mock(com.healthtrace.rights.audit.AuditService.class), clock);
        given(profileMapper.findActiveByAccountAndProfile(ACCOUNT, PROFILE)).willReturn(new ProfileRecord());
        given(visitMapper.findById(VISIT)).willReturn(visit(VISIT, PROFILE));
        given(reportMapper.findById(REPORT)).willReturn(report(REPORT, PROFILE, VisibilityStatus.NORMAL));
        given(reportMapper.findById(FOREIGN_REPORT)).willReturn(report(FOREIGN_REPORT, OTHER_PROFILE, VisibilityStatus.NORMAL));
        given(visitMapper.findLinkedReports(anyCollection())).willReturn(List.of());
        doAnswer(inv -> {
            Visit v = inv.getArgument(0);
            v.setId(VISIT);
            return 1;
        }).when(visitMapper).insert(any(Visit.class));
    }

    @Test
    void createStoresTrimmedFields() {
        VisitView view = service.create(ACCOUNT, PROFILE,
                new VisitInput(LocalDate.of(2026, 10, 27), "  市第一医院 ", " 呼吸内科 ", "  "));

        ArgumentCaptor<Visit> captor = ArgumentCaptor.forClass(Visit.class);
        verify(visitMapper).insert(captor.capture());
        Visit saved = captor.getValue();
        assertThat(saved.getProfileId()).isEqualTo(PROFILE);
        assertThat(saved.getCreatedBy()).isEqualTo(ACCOUNT);
        assertThat(saved.getHospitalText()).isEqualTo("市第一医院");
        assertThat(saved.getDepartment()).isEqualTo("呼吸内科");
        assertThat(saved.getNote()).isNull();
        assertThat(view.id()).isEqualTo(VISIT);
        assertThat(view.reports()).isEmpty();
    }

    @Test
    void createOnForeignProfileIsNotFound() {
        assertNotFound(() -> service.create(OTHER_ACCOUNT, PROFILE,
                new VisitInput(LocalDate.of(2026, 10, 27), "市第一医院", null, null)));
        verify(visitMapper, never()).insert(any());
    }

    @Test
    void createRejectsMissingHospitalAndFutureDate() {
        assertThatThrownBy(() -> service.create(ACCOUNT, PROFILE,
                new VisitInput(LocalDate.of(2026, 10, 27), "  ", null, null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.create(ACCOUNT, PROFILE,
                new VisitInput(LocalDate.of(2026, 10, 29), "市第一医院", null, null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.create(ACCOUNT, PROFILE,
                new VisitInput(null, "市第一医院", null, null)))
                .isInstanceOf(ApiException.class);
        verify(visitMapper, never()).insert(any());
    }

    @Test
    void createRejectsTooLongHospital() {
        assertThatThrownBy(() -> service.create(ACCOUNT, PROFILE,
                new VisitInput(LocalDate.of(2026, 10, 1), "院".repeat(161), null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("医院不能超过 160 个字符");
    }

    @Test
    void listPassesLimitToSqlAndCountsWhenPageIsFull() {
        given(visitMapper.findByProfile(PROFILE, 2)).willReturn(List.of(visit(3L, PROFILE), visit(2L, PROFILE)));
        given(visitMapper.countByProfile(PROFILE)).willReturn(3L);

        var page = service.list(ACCOUNT, PROFILE, 2);

        assertThat(page.items()).extracting(VisitView::id).containsExactly(3L, 2L);
        assertThat(page.total()).isEqualTo(3);
    }

    @Test
    void listWithoutLimitReturnsAllAndSkipsCount() {
        given(visitMapper.findByProfile(PROFILE, null)).willReturn(List.of(
                visit(3L, PROFILE), visit(2L, PROFILE), visit(1L, PROFILE)));

        var page = service.list(ACCOUNT, PROFILE, null);

        assertThat(page.items()).hasSize(3);
        assertThat(page.total()).isEqualTo(3);
        verify(visitMapper, never()).countByProfile(anyLong());
    }

    @Test
    void listForeignProfileIsNotFound() {
        assertNotFound(() -> service.list(OTHER_ACCOUNT, PROFILE, null));
        verify(visitMapper, never()).findByProfile(anyLong(), any());
    }

    @Test
    void getUnknownOrForeignVisitIsNotFound() {
        assertNotFound(() -> service.get(ACCOUNT, 999L));
        assertNotFound(() -> service.get(OTHER_ACCOUNT, VISIT));
    }

    @Test
    void updateVersionConflict() {
        given(visitMapper.update(any(), eq(0L))).willReturn(0);
        assertThatThrownBy(() -> service.update(ACCOUNT, VISIT, 0L,
                new VisitInput(LocalDate.of(2026, 10, 1), "市第二医院", null, null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT);
    }

    @Test
    void updateForeignVisitIsNotFound() {
        assertNotFound(() -> service.update(OTHER_ACCOUNT, VISIT, 0L,
                new VisitInput(LocalDate.of(2026, 10, 1), "市第二医院", null, null)));
        verify(visitMapper, never()).update(any(), anyLong());
    }

    @Test
    void deleteForeignVisitIsNotFound() {
        assertNotFound(() -> service.delete(OTHER_ACCOUNT, VISIT));
        verify(visitMapper, never()).delete(anyLong());
        service.delete(ACCOUNT, VISIT);
        verify(visitMapper).delete(VISIT);
    }

    @Test
    void linkSameProfileReport() {
        service.linkReport(ACCOUNT, VISIT, REPORT);
        verify(visitMapper).link(eq(VISIT), eq(REPORT), any());
    }

    @Test
    void linkReportOfOtherProfileIsNotFound() {
        assertNotFound(() -> service.linkReport(ACCOUNT, VISIT, FOREIGN_REPORT));
        verify(visitMapper, never()).link(anyLong(), anyLong(), any());
    }

    @Test
    void linkRemovedOrMissingReportIsNotFound() {
        given(reportMapper.findById(23L)).willReturn(report(23L, PROFILE, VisibilityStatus.DELETED));
        assertNotFound(() -> service.linkReport(ACCOUNT, VISIT, 23L));
        assertNotFound(() -> service.linkReport(ACCOUNT, VISIT, 404L));
        verify(visitMapper, never()).link(anyLong(), anyLong(), any());
    }

    @Test
    void linkByOtherAccountIsNotFound() {
        assertNotFound(() -> service.linkReport(OTHER_ACCOUNT, VISIT, REPORT));
        verify(visitMapper, never()).link(anyLong(), anyLong(), any());
    }

    @Test
    void unlinkOnlyRemovesLink() {
        service.unlinkReport(ACCOUNT, VISIT, REPORT);
        verify(visitMapper).unlink(VISIT, REPORT);
        assertNotFound(() -> service.unlinkReport(OTHER_ACCOUNT, VISIT, REPORT));
    }

    private static void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    private static Visit visit(long id, long profileId) {
        Visit v = new Visit();
        v.setId(id);
        v.setProfileId(profileId);
        v.setCreatedBy(ACCOUNT);
        v.setVisitDate(LocalDate.of(2026, 10, 1));
        v.setHospitalText("市第一医院");
        v.setVersion(0L);
        return v;
    }

    private static Report report(long id, long profileId, VisibilityStatus visibility) {
        Report r = new Report();
        r.setId(id);
        r.setProfileId(profileId);
        r.setVisibilityStatus(visibility);
        return r;
    }
}
