package com.healthtrace.report.service;

import com.healthtrace.report.domain.ProcessingStatus;
import com.healthtrace.report.domain.ReportType;
import com.healthtrace.report.service.ReportViews.ReportGroup;
import com.healthtrace.report.service.ReportViews.ReportListItem;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportGroupingTest {

    private static ReportListItem item(long id, String title, ReportType type, LocalDate date) {
        return new ReportListItem(id, title, type, date, ProcessingStatus.CONFIRMED,
                LocalDateTime.of(2026, 10, 1, 9, 0), 1, null, false, null);
    }

    @Test
    void cleanTitleStripsDatesBracketsAndSuffix() {
        assertThat(ReportGrouping.cleanTitle("血常规 2024-05-01")).isEqualTo("血常规");
        assertThat(ReportGrouping.cleanTitle("2024年5月 甲功七项报告")).isEqualTo("甲功七项");
        assertThat(ReportGrouping.cleanTitle("血常规（复查）")).isEqualTo("血常规");
        assertThat(ReportGrouping.cleanTitle("肝功能检验单")).isEqualTo("肝功能");
        assertThat(ReportGrouping.cleanTitle(null)).isEmpty();
    }

    @Test
    void groupsSimilarTitlesAndCounts() {
        List<ReportGroup> groups = ReportGrouping.group(List.of(
                item(5, "血常规 2024-06-01", ReportType.UNKNOWN, LocalDate.of(2024, 6, 1)),
                item(4, "甲功七项", ReportType.UNKNOWN, LocalDate.of(2024, 5, 20)),
                item(3, "血常规报告", ReportType.UNKNOWN, LocalDate.of(2024, 5, 1)),
                item(2, "甲功七项（复查）", ReportType.UNKNOWN, LocalDate.of(2024, 3, 1)),
                item(1, "血常规", ReportType.UNKNOWN, LocalDate.of(2024, 1, 1))));

        assertThat(groups).extracting(ReportGroup::label).containsExactly("血常规", "甲功七项");
        assertThat(groups.get(0).count()).isEqualTo(3);
        assertThat(groups.get(0).reports()).extracting(ReportListItem::id).containsExactly(5L, 3L, 1L);
        assertThat(groups.get(0).latestDate()).isEqualTo(LocalDate.of(2024, 6, 1));
        assertThat(groups.get(1).count()).isEqualTo(2);
    }

    @Test
    void untitledFallsBackToTypeThenUntitled() {
        List<ReportGroup> groups = ReportGrouping.group(List.of(
                item(3, null, ReportType.BLOOD_ROUTINE, LocalDate.of(2024, 6, 1)),
                item(2, "血常规", ReportType.UNKNOWN, LocalDate.of(2024, 5, 1)),
                item(1, "  ", ReportType.UNKNOWN, null)));

        assertThat(groups).extracting(ReportGroup::label).containsExactly("血常规", "未命名报告");
        assertThat(groups.get(0).count()).isEqualTo(2);
        assertThat(groups.get(1).key()).isEqualTo(ReportGrouping.UNTITLED_KEY);
        assertThat(groups.get(1).latestDate()).isNull();
    }

    @Test
    void emptyInputGivesNoGroups() {
        assertThat(ReportGrouping.group(List.of())).isEmpty();
    }
}
