package com.healthtrace.indicator.service;

import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.FieldErrorItem;
import com.healthtrace.indicator.domain.MetricDefinition;
import com.healthtrace.indicator.domain.TrendObservation;
import com.healthtrace.indicator.mapper.TrendMapper;
import com.healthtrace.indicator.service.TrendViews.Change;
import com.healthtrace.indicator.service.TrendViews.DateRange;
import com.healthtrace.indicator.service.TrendViews.MetricInfo;
import com.healthtrace.indicator.service.TrendViews.MetricSummary;
import com.healthtrace.indicator.service.TrendViews.NonNumericPoint;
import com.healthtrace.indicator.service.TrendViews.TrendGroup;
import com.healthtrace.indicator.service.TrendViews.TrendPoint;
import com.healthtrace.indicator.service.TrendViews.TrendView;
import com.healthtrace.profile.mapper.ProfileMapper;
import com.healthtrace.report.domain.ResultKind;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 指标趋势（FR-010/011）。
 * <ul>
 *   <li>只读已确认报告的 FORMAL 结果；档案无 ACTIVE 授权时统一 404，不泄露存在性。</li>
 *   <li>可比组 = 单位写法归一 + 标本 + 方法；组间不换算、不连线、不计算增减。</li>
 *   <li>增减只与同组最近此前日期的精确数值比较；前值为 0 不给百分比；同日多条不计算。</li>
 *   <li>非精确结果（小于/大于/定性/未知）只进列表。</li>
 * </ul>
 */
@Service
public class TrendService {

    /** 区间截止日按中国时间的“今天”计算。 */
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    static final String NOTE_PREVIOUS_ZERO = "PREVIOUS_ZERO";
    static final String NOTE_SAME_DAY = "SAME_DAY_AMBIGUOUS";

    private final TrendMapper trendMapper;
    private final ProfileMapper profileMapper;
    private final Clock clock;

    public TrendService(TrendMapper trendMapper, ProfileMapper profileMapper, Clock clock) {
        this.trendMapper = trendMapper;
        this.profileMapper = profileMapper;
        this.clock = clock;
    }

    public List<MetricSummary> listMetrics(long accountId, long profileId) {
        requireProfileAccess(accountId, profileId);
        Map<String, List<TrendObservation>> byCode = new LinkedHashMap<>();
        for (TrendObservation o : trendMapper.findFormalByProfile(profileId)) {
            byCode.computeIfAbsent(o.getMetricCode(), k -> new ArrayList<>()).add(o);
        }
        Map<String, MetricDefinition> definitions = new HashMap<>();
        for (MetricDefinition d : trendMapper.findAllDefinitions()) {
            definitions.put(d.getCode(), d);
        }
        List<MetricSummary> result = new ArrayList<>();
        for (Map.Entry<String, List<TrendObservation>> e : byCode.entrySet()) {
            MetricDefinition d = definitions.get(e.getKey());
            if (d == null) {
                continue;
            }
            List<TrendObservation> rows = e.getValue();
            LocalDate latest = rows.stream().map(TrendObservation::getExaminationDate)
                    .max(Comparator.naturalOrder()).orElse(null);
            Set<String> keys = new HashSet<>();
            rows.forEach(o -> keys.add(groupKey(o)));
            result.add(new MetricSummary(d.getCode(), d.getStandardName(), d.getStandardUnit(), rows.size(),
                    latest, keys.size()));
        }
        return result;
    }

    public TrendView getTrend(long accountId, long profileId, String metricCode, String rangeValue,
                              LocalDate customFrom, LocalDate customTo) {
        requireProfileAccess(accountId, profileId);
        DateRange range = resolveRange(rangeValue, customFrom, customTo);
        MetricDefinition definition = metricCode == null || metricCode.isBlank()
                ? null : trendMapper.findDefinitionByCode(metricCode.trim());
        if (definition == null) {
            throw validation("metricCode", "UNKNOWN", "不支持的指标");
        }
        List<TrendObservation> rows = trendMapper.findFormalByMetric(profileId, definition.getId(), range.to());
        List<TrendGroup> groups = buildGroups(rows, definition.getStandardUnit(), range);
        MetricInfo metric = new MetricInfo(definition.getCode(), definition.getStandardName(),
                definition.getStandardUnit(), definition.getComparabilityNote());
        return new TrendView(metric, range, groups, groups.size() > 1);
    }

    // ---------------------------------------------------------------- range

    DateRange resolveRange(String rangeValue, LocalDate customFrom, LocalDate customTo) {
        TrendRange range = TrendRange.parse(rangeValue);
        if (range == null) {
            throw validation("range", "UNKNOWN", "时间区间只能是 7d、30d、1y、5y 或 custom");
        }
        LocalDate today = LocalDate.now(clock.withZone(BUSINESS_ZONE));
        if (range != TrendRange.CUSTOM) {
            return new DateRange(range.fromFor(today), today);
        }
        List<FieldErrorItem> errors = new ArrayList<>();
        if (customFrom == null) {
            errors.add(new FieldErrorItem("from", "REQUIRED", "请选择开始日期"));
        }
        if (customTo == null) {
            errors.add(new FieldErrorItem("to", "REQUIRED", "请选择结束日期"));
        }
        if (errors.isEmpty()) {
            if (customFrom.isAfter(customTo)) {
                errors.add(new FieldErrorItem("from", "AFTER_END", "开始日期不能晚于结束日期"));
            } else if (customFrom.isBefore(customTo.minusYears(TrendRange.CUSTOM_MAX_YEARS))) {
                errors.add(new FieldErrorItem("from", "TOO_LONG", "自定义区间最长 20 年"));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
        }
        return new DateRange(customFrom, customTo);
    }

    // ---------------------------------------------------------------- grouping

    static String groupKey(TrendObservation o) {
        String unit = UnitNormalizer.normalize(o.getUnitText());
        String specimen = UnitNormalizer.normalizeText(o.getSpecimenText());
        String method = UnitNormalizer.normalizeText(o.getMethodText());
        return (unit.isEmpty() ? "-" : unit) + "|" + (specimen.isEmpty() ? "-" : specimen)
                + "|" + (method.isEmpty() ? "-" : method);
    }

    /**
     * rows 为截至区间末日的全部结果（按日期、id 升序）。增减使用窗口前的历史点计算，
     * 但只返回窗口内的点；窗口内无任何结果的组不返回。
     */
    static List<TrendGroup> buildGroups(List<TrendObservation> rows, String standardUnit, DateRange range) {
        Map<String, List<TrendObservation>> byKey = new LinkedHashMap<>();
        List<TrendObservation> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(TrendObservation::getExaminationDate)
                .thenComparing(TrendObservation::getObservationId));
        for (TrendObservation o : sorted) {
            byKey.computeIfAbsent(groupKey(o), k -> new ArrayList<>()).add(o);
        }
        String standardKey = UnitNormalizer.normalize(standardUnit);

        List<TrendGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<TrendObservation>> e : byKey.entrySet()) {
            List<TrendObservation> members = e.getValue();
            List<TrendObservation> exact = members.stream()
                    .filter(o -> o.getResultKind() == ResultKind.EXACT && o.getNumericValue() != null).toList();
            Map<LocalDate, Integer> perDay = new HashMap<>();
            exact.forEach(o -> perDay.merge(o.getExaminationDate(), 1, Integer::sum));

            List<TrendPoint> points = new ArrayList<>();
            TrendObservation previous = null;
            LocalDate previousDate = null;
            for (int i = 0; i < exact.size(); i++) {
                TrendObservation o = exact.get(i);
                LocalDate date = o.getExaminationDate();
                if (previousDate == null || !previousDate.equals(date)) {
                    // 最近此前日期的点：取上一日期的最后一条（同日多条时下面会判为歧义）
                    previous = i > 0 ? exact.get(i - 1) : null;
                    previousDate = date;
                }
                Change change = null;
                if (previous != null && !previous.getExaminationDate().equals(date)) {
                    boolean ambiguous = perDay.get(date) > 1 || perDay.get(previous.getExaminationDate()) > 1;
                    change = ambiguous ? new Change(null, null, NOTE_SAME_DAY)
                            : change(previous.getNumericValue(), o.getNumericValue());
                } else if (perDay.get(date) > 1) {
                    change = new Change(null, null, NOTE_SAME_DAY);
                }
                if (inRange(date, range)) {
                    points.add(new TrendPoint(o.getObservationId(), o.getReportId(), date, o.getOriginalValue(),
                            clean(o.getNumericValue()), o.getReferenceText(), clean(o.getReferenceLow()),
                            clean(o.getReferenceHigh()), o.getAbnormalFlag(), change));
                }
            }

            List<NonNumericPoint> nonNumeric = new ArrayList<>();
            for (TrendObservation o : members) {
                boolean isExact = o.getResultKind() == ResultKind.EXACT && o.getNumericValue() != null;
                if (!isExact && inRange(o.getExaminationDate(), range)) {
                    nonNumeric.add(new NonNumericPoint(o.getObservationId(), o.getReportId(), o.getExaminationDate(),
                            o.getOriginalValue(), o.getResultKind(), o.getReferenceText(), o.getAbnormalFlag()));
                }
            }
            if (points.isEmpty() && nonNumeric.isEmpty()) {
                continue;
            }
            TrendObservation first = members.get(0);
            String unitKey = UnitNormalizer.normalize(first.getUnitText());
            groups.add(new TrendGroup(e.getKey(), blankToNull(first.getUnitText()),
                    blankToNull(first.getSpecimenText()), blankToNull(first.getMethodText()),
                    !unitKey.isEmpty() && unitKey.equals(standardKey), points, nonNumeric));
        }
        return groups;
    }

    static Change change(BigDecimal previous, BigDecimal current) {
        BigDecimal absolute = clean(current.subtract(previous));
        if (previous.signum() == 0) {
            return new Change(absolute, null, NOTE_PREVIOUS_ZERO);
        }
        BigDecimal percent = current.subtract(previous).multiply(BigDecimal.valueOf(100))
                .divide(previous.abs(), 2, RoundingMode.HALF_UP);
        return new Change(absolute, percent, null);
    }

    /** 去掉 DECIMAL(20,8) 的尾随 0，并避免科学计数法输出。 */
    static BigDecimal clean(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal v = value.stripTrailingZeros();
        return v.scale() < 0 ? v.setScale(0) : v;
    }

    private static boolean inRange(LocalDate date, DateRange range) {
        return !date.isBefore(range.from()) && !date.isAfter(range.to());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private void requireProfileAccess(long accountId, long profileId) {
        if (profileMapper.findActiveByAccountAndProfile(accountId, profileId) == null) {
            throw new ApiException(ErrorCode.PROFILE_NOT_FOUND);
        }
    }

    private static ApiException validation(String field, String reason, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message,
                List.of(new FieldErrorItem(field, reason, message)));
    }
}
