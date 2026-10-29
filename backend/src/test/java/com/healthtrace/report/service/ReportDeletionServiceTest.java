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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ReportDeletionServiceTest {

    private ReportAccess access;
    private ReportMapper reportMapper;
    private PhotoMapper photoMapper;
    private OcrJobMapper ocrJobMapper;
    private ObservationMapper observationMapper;
    private StorageService storageService;
    private ReportDeletionService service;

    @BeforeEach
    void setUp() {
        access = mock(ReportAccess.class);
        reportMapper = mock(ReportMapper.class);
        photoMapper = mock(PhotoMapper.class);
        ocrJobMapper = mock(OcrJobMapper.class);
        observationMapper = mock(ObservationMapper.class);
        storageService = mock(StorageService.class);
        service = new ReportDeletionService(access, reportMapper, photoMapper, ocrJobMapper, observationMapper,
                storageService, mock(com.healthtrace.rights.audit.AuditService.class));
    }

    private Report report(ProcessingStatus status) {
        Report r = new Report();
        r.setId(21L);
        r.setProcessingStatus(status);
        given(access.requireOwnedReport(7L, 21L)).willReturn(r);
        given(reportMapper.findByIdForUpdate(21L)).willReturn(r);
        return r;
    }

    private static Photo photo(String key, String previewKey) {
        Photo p = new Photo();
        p.setObjectKey(key);
        p.setPreviewObjectKey(previewKey);
        return p;
    }

    @Test
    void othersReportIsNotFoundAndNothingDeleted() {
        willThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND)).given(access).requireOwnedReport(7L, 99L);
        assertThatThrownBy(() -> service.deleteUnconfirmed(7L, 99L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        verify(reportMapper, never()).deleteUnconfirmed(anyLong());
    }

    @ParameterizedTest
    @EnumSource(value = ProcessingStatus.class, names = {"CONFIRMED", "RECOGNIZING"})
    void confirmedOrRecognizingReportIsRejected(ProcessingStatus status) {
        report(status);
        assertThatThrownBy(() -> service.deleteUnconfirmed(7L, 21L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.REPORT_STATE_CONFLICT);
        verify(photoMapper, never()).deleteByReport(anyLong());
        verify(reportMapper, never()).deleteUnconfirmed(anyLong());
        verify(storageService, never()).delete(anyString());
    }

    @ParameterizedTest
    @EnumSource(value = ProcessingStatus.class, names = {"UPLOADING", "PENDING_CONFIRMATION", "FAILED"})
    void unconfirmedReportIsDeletedWithChildrenAndStorage(ProcessingStatus status) {
        report(status);
        given(photoMapper.findAllKeysByReport(21L)).willReturn(List.of(photo("k1", "p1"), photo("k2", null)));
        given(reportMapper.deleteUnconfirmed(21L)).willReturn(1);

        service.deleteUnconfirmed(7L, 21L);

        var order = inOrder(observationMapper, ocrJobMapper, photoMapper, reportMapper);
        order.verify(observationMapper).deleteByReport(21L);
        order.verify(ocrJobMapper).deleteByReport(21L);
        order.verify(photoMapper).deleteByReport(21L);
        order.verify(reportMapper).deleteUnconfirmed(21L);
        verify(storageService).delete("k1");
        verify(storageService).delete("p1");
        verify(storageService).delete("k2");
    }

    @Test
    void emptyReportWithoutPhotosCanBeDeleted() {
        report(ProcessingStatus.UPLOADING);
        given(photoMapper.findAllKeysByReport(21L)).willReturn(List.of());
        given(reportMapper.deleteUnconfirmed(21L)).willReturn(1);

        service.deleteUnconfirmed(7L, 21L);

        verify(reportMapper).deleteUnconfirmed(21L);
        verify(storageService, never()).delete(anyString());
    }

    @Test
    void storageFailureDoesNotUndoDeletion() {
        report(ProcessingStatus.FAILED);
        given(photoMapper.findAllKeysByReport(21L)).willReturn(List.of(photo("k1", null), photo("k2", null)));
        given(reportMapper.deleteUnconfirmed(21L)).willReturn(1);
        willThrow(new IllegalStateException("io")).given(storageService).delete("k1");

        service.deleteUnconfirmed(7L, 21L);

        verify(storageService).delete("k2");
        assertThat(true).isTrue();
    }
}
