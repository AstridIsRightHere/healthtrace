package com.healthtrace.indicator.service;

import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.FieldErrorItem;
import com.healthtrace.indicator.domain.MetricAggregate;
import com.healthtrace.indicator.domain.MetricDefinition;
import com.healthtrace.indicator.domain.TrendObservation;
import com.healthtrace.indicator.mapper.TrendMapper;
import com.healthtrace.indicator.service.TrendViews.Change;
import com.healthtrace.indicator.service.TrendViews.DateRange;
import com.healthtrace.indicator.service.TrendViews.LatestObservation;
import com.healthtrace.indicator.service.TrendViews.MetricInfo;
import com.healthtrace.indicator.service.TrendViews.MetricLatestSummaries;
import com.healthtrace.indicator.service.TrendViews.MetricLatestSummary;
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
import java.util.LinkedHashSet;
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
    public static final String NOTE_PREVIOUS_ZERO = "PREVIOUS_ZERO";
    static final String NOTE_SAME_DAY = "SAME_DAY_AMBIGUOUS";
    /** 未纳入标准字典的项目 code 前缀（RAW: + 归一后的报告原名）。 */
    public static final String RAW_PREFIX = "RAW:";
    /** 最新值摘要一次最多查询的指标数（与关注列表上限一致）。 */
    static final int MAX_SUMMARY_CODES = 20;

    private final TrendMapper trendMapper;
    private final ProfileMapper profileMapper;
    private final Clock clock;

    public TrendService(TrendMapper trendMapper, ProfileMapper profileMapper, Clock clock) {
        this.trendMapper = trendMapper;
        this.profileMapper = profileMapper;
        this.clock = clock;
    }

    /**
     * “有数据的指标”列表。SQL 按原始写法分组计数（aggregateFormalByProfile），服务层再按归一规则合并：
     * 列表顺序 = 各指标首行在原排序中的位置（MIN first_pos），未映射项目的展示名/单位取最后一行（MAX last_pos），
     * 与逐行计算的结果一致。
     */
    public List<MetricSummary> listMetrics(long accountId, long profileId) {
        requireProfileAccess(accountId, profileId);
        Map<String, List<MetricAggregate>> byCode = new LinkedHashMap<>();
        // 查询已按 first_pos 升序，LinkedHashMap 的插入顺序即各指标首行的顺序
        for (MetricAggregate a : trendMapper.aggregateFormalByProfile(profileId)) {
            String key = a.getMetricCode() != null && !a.getMetricCode().isBlank()
                    ? a.getMetricCode() : RAW_PREFIX + normalizeName(a.getOriginalName());
            byCode.computeIfAbsent(key, k -> new ArrayList<>()).add(a);
        }
        Map<String, MetricDefinition> definitions = new HashMap<>();
        for (MetricDefinition d : trendMapper.findAllDefinitions()) {
            definitions.put(d.getCode(), d);
        }
        List<MetricSummary> result = new ArrayList<>();
        for (Map.Entry<String, List<MetricAggregate>> e : byCode.entrySet()) {
            List<MetricAggregate> parts = e.getValue();
            int count = Math.toIntExact(parts.stream().mapToLong(MetricAggregate::getObservationCount).sum());
            LocalDate latest = parts.stream().map(MetricAggregate::getLatestDate)
                    .max(Comparator.naturalOrder()).orElse(null);
            Set<String> keys = new HashSet<>();
            parts.forEach(a -> keys.add(groupKey(a.getUnitText(), a.getSpecimenText(), a.getMethodText())));
            if (e.getKey().startsWith(RAW_PREFIX)) {
                // 未纳入标准字典的项目：按报告原名汇总，单位取最近一次（原排序最后一行）的写法，仅作展示
                MetricAggregate last = parts.stream()
                        .max(Comparator.comparingLong(MetricAggregate::getLastPos)).orElseThrow();
                result.add(new MetricSummary(e.getKey(), displayName(last.getOriginalName()),
                        blankToEmpty(last.getUnitText()), count, latest, keys.size(), false));
                continue;
            }
            MetricDefinition d = definitions.get(e.getKey());
            if (d == null) {
                continue;
            }
            result.add(new MetricSummary(d.getCode(), d.getStandardName(), d.getStandardUnit(), count,
                    latest, keys.size(), true));
        }
        return result;
    }

    /**
     * 关注卡片的最新值摘要（固定 2 次 SQL：字典 + IN 查询）。codes 逗号分隔，去重后 1~20 个。
     * latest = 各可比组中最新的精确数值点（同日取 observationId 较大者），change 与趋势接口中该点一致。
     */
    public MetricLatestSummaries getLatestSummaries(long accountId, long profileId, String codes) {
        requireProfileAccess(accountId, profileId);
        Set<String> requested = new LinkedHashSet<>();
        if (codes != null) {
            for (String c : codes.split(",")) {
                String code = c.trim();
                if (!code.isEmpty()) {
                    requested.add(code);
                }
            }
        }
        if (requested.isEmpty()) {
            throw validation("codes", "NotBlank", "请指定指标");
        }
        if (requested.size() > MAX_SUMMARY_CODES) {
            throw validation("codes", "Size", "一次最多查询 " + MAX_SUMMARY_CODES + " 个指标");
        }
        Map<String, MetricDefinition> definitions = new HashMap<>();
        for (MetricDefinition d : trendMapper.findAllDefinitions()) {
            definitions.put(d.getCode(), d);
        }
        List<MetricDefinition> targets = new ArrayList<>();
        for (String code : requested) {
            MetricDefinition d = definitions.get(code);
            if (d == null) {
                throw validation("codes", "UnknownMetric", "不支持的指标：" + code);
            }
            targets.add(d);
        }
        LocalDate today = LocalDate.now(clock.withZone(BUSINESS_ZONE));
        Map<String, List<TrendObservation>> byCode = new HashMap<>();
        for (TrendObservation o : trendMapper.findRecentExactByMetrics(profileId,
                targets.stream().map(MetricDefinition::getId).toList(), today)) {
            byCode.computeIfAbsent(o.getMetricCode(), k -> new ArrayList<>()).add(o);
        }
        DateRange all = new DateRange(LocalDate.MIN, today);
        List<MetricLatestSummary> items = new ArrayList<>();
        for (MetricDefinition d : targets) {
            List<TrendObservation> rows = byCode.getOrDefault(d.getCode(), List.of());
            Map<Long, TrendObservation> byId = new HashMap<>();
            rows.forEach(o -> byId.put(o.getObservationId(), o));
            TrendPoint best = null;
            for (TrendGroup g : buildGroups(rows, d.getStandardUnit(), all)) {
                for (TrendPoint p : g.points()) {
                    if (best == null || p.examinationDate().isAfter(best.examinationDate())
                            || (p.examinationDate().equals(best.examinationDate())
                            && p.observationId() > best.observationId())) {
                        best = p;
                    }
                }
            }
            LatestObservation latest = null;
            Change change = null;
            if (best != null) {
                TrendObservation o = byId.get(best.observationId());
                latest = new LatestObservation(best.observationId(), best.reportId(), best.examinationDate(),
                        best.originalValue(), best.numericValue(), blankToNull(o.getUnitText()), best.abnormalFlag());
                change = best.change();
            }
            items.add(new MetricLatestSummary(d.getCode(), d.getStandardName(), d.getStandardUnit(), latest, change));
        }
        return new MetricLatestSummaries(items);
    }

    /** 列表/趋势共用的归组 code：已映射用标准 code，未映射用 RAW: + 归一后的原名。 */
    public static String metricKey(TrendObservation o) {
        if (o.getMetricCode() != null && !o.getMetricCode().isBlank()) {
            return o.getMetricCode();
        }
        return RAW_PREFIX + normalizeName(o.getOriginalName());
    }

    /** 原名归一：去空白、去报告中常见的 ★☆*※ 标记、全角转半角括号、转大写；不做同义合并（DEC-015）。 */
    public static String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("[\\s\\u3000\\u00A0★☆*※]+", "")
                .replace('（', '(').replace('）', ')')
                .toUpperCase(java.util.Locale.ROOT);
    }

    public static String displayName(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("^[\\s\\u3000★☆*※]+", "").trim();
    }

    private static String blankToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    public TrendView getTrend(long accountId, long profileId, String metricCode, String rangeValue,
                              LocalDate customFrom, LocalDate customTo) {
        requireProfileAccess(accountId, profileId);
        DateRange range = resolveRange(rangeValue, customFrom, customTo);
        String code = metricCode == null ? "" : metricCode.trim();
        if (code.startsWith(RAW_PREFIX)) {
            return getRawTrend(profileId, code, range);
        }
        MetricDefinition definition = code.isEmpty() ? null : trendMapper.findDefinitionByCode(code);
        if (definition == null) {
            throw validation("metricCode", "UNKNOWN", "不支持的指标");
        }
        // 窗口内结果由 SQL 按 [from, to] 取出；窗口前只补取 findFormalHistoryByMetric 的最少历史行
        // （各原始写法最早一条 + 最近精确日期的全部行），使可比组顺序/展示写法与窗口首点的增减与全量计算一致
        List<TrendObservation> rows = new ArrayList<>(
                trendMapper.findFormalHistoryByMetric(profileId, definition.getId(), range.from()));
        rows.addAll(trendMapper.findFormalByMetric(profileId, definition.getId(), range.from(), range.to()));
        List<TrendGroup> groups = buildGroups(rows, definition.getStandardUnit(), range);
        MetricInfo metric = new MetricInfo(definition.getCode(), definition.getStandardName(),
                definition.getStandardUnit(), definition.getComparabilityNote());
        return new TrendView(metric, range, groups, groups.size() > 1);
    }

    /**
     * 未纳入标准字典的项目：只按“归一后的报告原名”精确归组，不做同义合并；
     * 没有标准单位，可比组仍按单位/标本/方法区分。
     */
    private TrendView getRawTrend(long profileId, String code, DateRange range) {
        String key = code.substring(RAW_PREFIX.length());
        if (key.isEmpty()) {
            throw validation("metricCode", "UNKNOWN", "不支持的指标");
        }
        List<TrendObservation> rows = trendMapper.findFormalUnmapped(profileId, range.to()).stream()
                .filter(o -> key.equals(normalizeName(o.getOriginalName())))
                .toList();
        if (rows.isEmpty()) {
            // 截至区间末日没有该原名的结果：返回空分组，不区分“从未有过”与“区间之后才有”
            return new TrendView(new MetricInfo(code, key, "", null), range, List.of(), false);
        }
        TrendObservation last = rows.get(rows.size() - 1);
        List<TrendGroup> groups = buildGroups(rows, null, range);
        MetricInfo metric = new MetricInfo(code, displayName(last.getOriginalName()),
                blankToEmpty(last.getUnitText()), "该项目暂未纳入标准指标字典，按报告原名汇总，仅供查看，不与其他名称合并。");
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

    public static String groupKey(TrendObservation o) {
        return groupKey(o.getUnitText(), o.getSpecimenText(), o.getMethodText());
    }

    static String groupKey(String unitText, String specimenText, String methodText) {
        String unit = UnitNormalizer.normalize(unitText);
        String specimen = UnitNormalizer.normalizeText(specimenText);
        String method = UnitNormalizer.normalizeText(methodText);
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

    public static Change change(BigDecimal previous, BigDecimal current) {
        BigDecimal absolute = clean(current.subtract(previous));
        if (previous.signum() == 0) {
            return new Change(absolute, null, NOTE_PREVIOUS_ZERO);
        }
        BigDecimal percent = current.subtract(previous).multiply(BigDecimal.valueOf(100))
                .divide(previous.abs(), 2, RoundingMode.HALF_UP);
        return new Change(absolute, percent, null);
    }

    /** 去掉 DECIMAL(20,8) 的尾随 0，并避免科学计数法输出。 */
    public static BigDecimal clean(BigDecimal value) {
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
