package com.healthtrace.report.service;

import com.healthtrace.report.domain.Photo;
import com.healthtrace.report.domain.ProcessingStatus;
import com.healthtrace.report.domain.Report;
import com.healthtrace.report.mapper.ObservationMapper;
import com.healthtrace.report.mapper.OcrJobMapper;
import com.healthtrace.report.mapper.PhotoMapper;
import com.healthtrace.report.mapper.ReportMapper;
import com.healthtrace.report.mapper.ReportRevisionMapper;
import com.healthtrace.rights.deletion.DeletionEventType;
import com.healthtrace.rights.deletion.DeletionLedgerService;
import com.healthtrace.visit.mapper.VisitMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** FR-016：任意状态都删除；外键顺序；对象键（原图 + 预览）交给账本/outbox；报告不存在返回 0。 */
class ReportPurgerTest {

    private ReportMapper reportMapper;
    private ReportRevisionMapper revisionMapper;
    private VisitMapper visitMapper;
    private ObservationMapper observationMapper;
    private OcrJobMapper ocrJobMapper;
    private PhotoMapper photoMapper;
    private DeletionLedgerService ledgerService;
    private ReportPurger purger;

    @BeforeEach
    void setUp() {
        reportMapper = mock(ReportMapper.class);
        revisionMapper = mock(ReportRevisionMapper.class);
        visitMapper = mock(VisitMapper.class);
        observationMapper = mock(ObservationMapper.class);
        ocrJobMapper = mock(OcrJobMapper.class);
        photoMapper = mock(PhotoMapper.class);
        ledgerService = mock(DeletionLedgerService.class);
        purger = new ReportPurger(reportMapper, revisionMapper, visitMapper, observationMapper, ocrJobMapper,
                photoMapper, ledgerService);
    }

    private static Photo photo(String key, String previewKey) {
        Photo p = new Photo();
        p.setObjectKey(key);
        p.setPreviewObjectKey(previewKey);
        return p;
    }

    @ParameterizedTest
    @EnumSource(ProcessingStatus.class)
    void everyStatusIsPurgedInForeignKeyOrder(ProcessingStatus status) {
        Report r = new Report();
        r.setId(21L);
        r.setProcessingStatus(status);
        given(reportMapper.findByIdForUpdate(21L)).willReturn(r);
        given(photoMapper.findAllKeysByReport(21L)).willReturn(List.of(photo("k1", "p1"), photo("k2", null)));
        given(revisionMapper.deleteByReport(21L)).willReturn(1);
        given(visitMapper.unlinkReport(21L)).willReturn(1);
        given(observationMapper.deleteByReport(21L)).willReturn(3);
        given(ocrJobMapper.deleteByReport(21L)).willReturn(1);
        given(photoMapper.deleteByReport(21L)).willReturn(2);
        given(reportMapper.deleteById(21L)).willReturn(1);

        assertThat(purger.purge(21L)).isEqualTo(9);

        var order = inOrder(revisionMapper, visitMapper, observationMapper, ocrJobMapper, photoMapper, reportMapper,
                ledgerService);
        order.verify(revisionMapper).deleteByReport(21L);
        order.verify(visitMapper).unlinkReport(21L);
        order.verify(observationMapper).deleteByReport(21L);
        order.verify(ocrJobMapper).deleteByReport(21L);
        order.verify(photoMapper).deleteByReport(21L);
        order.verify(reportMapper).deleteById(21L);
        order.verify(ledgerService).recordWithStorage(DeletionEventType.REPORT_PURGE, 21L, 9,
                List.of("k1", "p1", "k2"));
    }

    @Test
    void missingReportReturnsZeroWithoutLedger() {
        given(reportMapper.findByIdForUpdate(99L)).willReturn(null);

        assertThat(purger.purge(99L)).isZero();

        verify(reportMapper, never()).deleteById(anyLong());
        verify(ledgerService, never()).recordWithStorage(any(), anyLong(), anyInt(), any());
    }
}
