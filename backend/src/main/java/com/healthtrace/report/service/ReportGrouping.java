package com.healthtrace.report.service;

import com.healthtrace.report.domain.ReportType;
import com.healthtrace.report.service.ReportViews.ReportGroup;
import com.healthtrace.report.service.ReportViews.ReportListItem;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 已确认报告按“报告种类”聚合（FR-010 报告对比的入口）。
 * <p>规则（保守、可解释，不做同义词合并，参照 DEC-015 的思路）：
 * <ol>
 *   <li>名称来源：有标题用标题；没有标题而类型已识别（血常规/肝功能/肾功能）用类型名；都没有归入“未命名报告”。</li>
 *   <li>标题清洗：去掉日期（2024-05-01、2024年5月、2024.5 等）、括号内备注、末尾的“报告/报告单/检验单/检查单/化验单”。</li>
 *   <li>分组键 = 清洗后名称去空白与常见标点、转大写，因此“血常规 2024-05”“血常规报告”“血常规（复查）”归为一组。</li>
 * </ol>
 * 组按最近检查日期倒序；组内保持输入顺序（检查日期、id 倒序）。
 */
public final class ReportGrouping {

    static final String UNTITLED_KEY = "UNTITLED";
    static final String UNTITLED_LABEL = "未命名报告";

    private static final Pattern DATE = Pattern.compile(
            "\\d{4}\\s*[-/.年]\\s*\\d{1,2}(\\s*[-/.月]\\s*\\d{1,2}\\s*日?|\\s*月)?|\\d{8}");
    private static final Pattern BRACKETS = Pattern.compile("[(（\\[【][^)）\\]】]*[)）\\]】]");
    private static final Pattern SUFFIX = Pattern.compile("(报告单|报告|检验单|检查单|化验单)$");
    private static final Pattern KEY_STRIP = Pattern.compile("[\\s\\u3000\\u00A0·•\\-_—,，.。:：;；/\\\\|]+");
    private static final Pattern EDGE_PUNCT = Pattern.compile("^[\\s\\u3000·•\\-_—,，:：;；/|]+|[\\s\\u3000·•\\-_—,，:：;；/|]+$");

    private ReportGrouping() {
    }

    public static List<ReportGroup> group(List<ReportListItem> reports) {
        Map<String, List<ReportListItem>> byKey = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (ReportListItem r : reports) {
            String label = labelOf(r.title(), r.reportType());
            String key = keyOf(label);
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
            labels.putIfAbsent(key, label);
        }
        List<ReportGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<ReportListItem>> e : byKey.entrySet()) {
            List<ReportListItem> items = List.copyOf(e.getValue());
            LocalDate latest = items.stream().map(ReportListItem::examinationDate)
                    .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
            groups.add(new ReportGroup(e.getKey(), labels.get(e.getKey()), items.size(), latest, items));
        }
        groups.sort(Comparator.comparing(ReportGroup::latestDate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ReportGroup::label));
        return groups;
    }

    /** 展示名：清洗后的标题，或类型名，或“未命名报告”。 */
    static String labelOf(String title, ReportType type) {
        String cleaned = cleanTitle(title);
        if (!cleaned.isEmpty()) {
            return cleaned;
        }
        String typeLabel = typeLabel(type);
        return typeLabel != null ? typeLabel : UNTITLED_LABEL;
    }

    static String keyOf(String label) {
        if (UNTITLED_LABEL.equals(label)) {
            return UNTITLED_KEY;
        }
        String key = KEY_STRIP.matcher(label).replaceAll("").toUpperCase(Locale.ROOT);
        return key.isEmpty() ? UNTITLED_KEY : key;
    }

    static String cleanTitle(String title) {
        if (title == null) {
            return "";
        }
        String s = DATE.matcher(title).replaceAll(" ");
        s = BRACKETS.matcher(s).replaceAll(" ");
        s = EDGE_PUNCT.matcher(s).replaceAll("").strip();
        s = SUFFIX.matcher(s).replaceAll("").strip();
        s = EDGE_PUNCT.matcher(s).replaceAll("").strip();
        return s.replaceAll("[\\s\\u3000]+", " ");
    }

    static String typeLabel(ReportType type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case BLOOD_ROUTINE -> "血常规";
            case LIVER -> "肝功能";
            case KIDNEY -> "肾功能";
            case OTHER, UNKNOWN -> null;
        };
    }
}
