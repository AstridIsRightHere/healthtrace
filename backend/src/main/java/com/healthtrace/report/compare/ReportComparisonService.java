package com.healthtrace.report.compare;

import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.FieldErrorItem;
import com.healthtrace.indicator.domain.MetricDefinition;
import com.healthtrace.indicator.domain.TrendObservation;
import com.healthtrace.indicator.mapper.TrendMapper;
import com.healthtrace.indicator.service.TrendService;
import com.healthtrace.indicator.service.TrendViews.Change;
import com.healthtrace.report.compare.ComparisonViews.ComparisonRow;
import com.healthtrace.report.compare.ComparisonViews.ComparisonView;
import com.healthtrace.report.compare.ComparisonViews.ObservationCell;
import com.healthtrace.report.compare.ComparisonViews.ReportBrief;
import com.healthtrace.report.compare.ComparisonViews.RowStatus;
import com.healthtrace.report.domain.ProcessingStatus;
import com.healthtrace.report.domain.Report;
import com.healthtrace.report.domain.ResultKind;
import com.healthtrace.report.service.ReportAccess;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 两份已确认报告的逐项对比（FR-010/011）。
 * <ul>
 *   <li>两份报告都须可访问、已确认且属于同一档案，否则统一 404 HT-3001，不泄露存在性。</li>
 *   <li>匹配键与趋势一致：标准指标用 code，未映射用 RAW: + 归一原名；不做同义合并（DEC-015）。</li>
 *   <li>单位写法归一 + 标本 + 方法一致才可比；不做单位换算。</li>
 * </ul>
 */
@Service
public class ReportComparisonService {

    private final ReportAccess reportAccess;
    private final TrendMapper trendMapper;

    public ReportComparisonService(ReportAccess reportAccess, TrendMapper trendMapper) {
        this.reportAccess = reportAccess;
        this.trendMapper = trendMapper;
    }

    public ComparisonView compare(long accountId, long baseReportId, long targetReportId) {
        if (baseReportId == targetReportId) {
            String message = "请选择两份不同的报告";
            throw new ApiException(ErrorCode.VALIDATION_FAILED, message,
                    List.of(new FieldErrorItem("targetReportId", "SameReport", message)));
        }
        Report base = requireComparable(accountId, baseReportId);
        Report target = requireComparable(accountId, targetReportId);
        if (!base.getProfileId().equals(target.getProfileId())) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        Map<String, List<TrendObservation>> baseByKey = byKey(trendMapper.findFormalByReport(baseReportId));
        Map<String, List<TrendObservation>> targetByKey = byKey(trendMapper.findFormalByReport(targetReportId));
        Map<String, MetricDefinition> definitions = new HashMap<>();
        for (MetricDefinition d : trendMapper.findAllDefinitions()) {
            definitions.put(d.getCode(), d);
        }

        Set<String> keys = new LinkedHashSet<>(baseByKey.keySet());
        keys.addAll(targetByKey.keySet());
        List<ComparisonRow> rows = new ArrayList<>();
        for (String key : keys) {
            rows.add(buildRow(key, baseByKey.get(key), targetByKey.get(key), definitions.get(key)));
        }
        // 标准指标按字典 sort_order，其后为未映射项目按名称
        rows.sort(Comparator.comparing((ComparisonRow r) -> !r.standard())
                .thenComparingInt(r -> r.standard() && definitions.get(r.key()) != null
                        ? definitions.get(r.key()).getSortOrder() : Integer.MAX_VALUE)
                .thenComparing(ComparisonRow::name)
                .thenComparing(ComparisonRow::key));
        return new ComparisonView(brief(base), brief(target), rows);
    }

    private Report requireComparable(long accountId, long reportId) {
        Report report = reportAccess.requireOwnedReport(accountId, reportId);
        if (report.getProcessingStatus() != ProcessingStatus.CONFIRMED) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return report;
    }

    /** 按匹配键分组，组内按观测 id 升序（歧义时展示第一条）。 */
    private static Map<String, List<TrendObservation>> byKey(List<TrendObservation> rows) {
        List<TrendObservation> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(TrendObservation::getObservationId));
        Map<String, List<TrendObservation>> map = new LinkedHashMap<>();
        for (TrendObservation o : sorted) {
            map.computeIfAbsent(TrendService.metricKey(o), k -> new ArrayList<>()).add(o);
        }
        return map;
    }

    private static ComparisonRow buildRow(String key, List<TrendObservation> baseRows,
                                          List<TrendObservation> targetRows, MetricDefinition definition) {
        TrendObservation b = baseRows == null ? null : baseRows.get(0);
        TrendObservation t = targetRows == null ? null : targetRows.get(0);
        TrendObservation any = b != null ? b : t;
        boolean standard = !key.startsWith(TrendService.RAW_PREFIX);
        String name = standard && definition != null ? definition.getStandardName()
                : TrendService.displayName(any.getOriginalName());

        RowStatus status;
        Change change = null;
        if (b == null) {
            status = RowStatus.TARGET_ONLY;
        } else if (t == null) {
            status = RowStatus.BASE_ONLY;
        } else if (baseRows.size() > 1 || targetRows.size() > 1) {
            status = RowStatus.AMBIGUOUS;
        } else if (!TrendService.groupKey(b).equals(TrendService.groupKey(t))) {
            status = RowStatus.NOT_COMPARABLE;
        } else {
            status = RowStatus.COMPARABLE;
            if (isExact(b) && isExact(t)) {
                change = TrendService.change(b.getNumericValue(), t.getNumericValue());
            }
        }
        return new ComparisonRow(key, name, standard, status, cell(b), cell(t), change);
    }

    /** 与趋势画点一致的精确数值判定。 */
    private static boolean isExact(TrendObservation o) {
        return o.getResultKind() == ResultKind.EXACT && o.getNumericValue() != null;
    }

    private static ObservationCell cell(TrendObservation o) {
        if (o == null) {
            return null;
        }
        return new ObservationCell(o.getObservationId(), o.getOriginalValue(), TrendService.clean(o.getNumericValue()),
                o.getResultKind(), o.getUnitText(), o.getSpecimenText(), o.getMethodText(), o.getReferenceText(),
                o.getAbnormalFlag());
    }

    private static ReportBrief brief(Report r) {
        return new ReportBrief(r.getId(), r.getTitle(), r.getExaminationDate());
    }
}
