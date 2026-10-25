package com.healthtrace.report.ocr;

import com.healthtrace.report.ocr.OcrModels.OcrItem;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析百度“医疗检验报告单识别”返回的 words_result（纯函数，便于单测）。
 * <p>
 * 结构：{@code words_result.CommonData[] = {word_name, word, probability?}}（报告头信息），
 * {@code words_result.Item[][] = [{word_name: "项目名称"|"结果"|"单位"|"参考区间"..., word, probability?}]}（每行检验项目）。
 * 字段名存在别名差异，解析保持宽容；probability 可能是数字或 {average,min,variance} 对象，缺失时置信度为 null。
 */
public final class BaiduMedicalReportParser {

    private static final Set<String> NAME_KEYS = Set.of("项目名称", "名称", "检验项目", "项目", "中文名称");
    private static final Set<String> VALUE_KEYS = Set.of("结果", "检验结果", "测定值", "结果值");
    private static final Set<String> UNIT_KEYS = Set.of("单位");
    private static final Set<String> REFERENCE_KEYS = Set.of("参考区间", "参考范围", "参考值", "参考值范围");
    /** 按优先级取检查日期：采样 > 检验 > 送检/接收 > 报告。 */
    private static final List<String> DATE_KEY_PRIORITY = List.of("采样", "检验", "送检", "接收", "报告");
    private static final Pattern DATE = Pattern.compile("(\\d{4})\\s*[-/.年]\\s*(\\d{1,2})\\s*[-/.月]\\s*(\\d{1,2})");

    private BaiduMedicalReportParser() {
    }

    /** 一页的解析结果。 */
    public record PageResult(List<OcrItem> items, LocalDate examinationDate, List<String> textLines) {
    }

    public static PageResult parse(JsonNode wordsResult, Long sourcePhotoId) {
        List<String> lines = new ArrayList<>();
        LocalDate date = null;
        int datePriority = Integer.MAX_VALUE;

        JsonNode common = wordsResult == null ? null : wordsResult.get("CommonData");
        if (common != null && common.isArray()) {
            for (int i = 0; i < common.size(); i++) {
                JsonNode field = common.get(i);
                String key = text(field.get("word_name"));
                String word = text(field.get("word"));
                if (key == null || word == null) {
                    continue;
                }
                lines.add(key + "：" + word);
                int priority = datePriority(key);
                if (priority < datePriority) {
                    LocalDate parsed = parseDate(word);
                    if (parsed != null) {
                        date = parsed;
                        datePriority = priority;
                    }
                }
            }
        }

        List<OcrItem> items = new ArrayList<>();
        JsonNode rows = wordsResult == null ? null : wordsResult.get("Item");
        if (rows != null && rows.isArray()) {
            for (int i = 0; i < rows.size(); i++) {
                OcrItem item = parseRow(rows.get(i), sourcePhotoId);
                if (item != null) {
                    items.add(item);
                    lines.add(String.join(" ", nonNull(item.name(), item.valueText(), item.unitText(),
                            item.referenceText())));
                }
            }
        }
        return new PageResult(items, date, lines);
    }

    private static OcrItem parseRow(JsonNode row, Long sourcePhotoId) {
        if (row == null || !row.isArray()) {
            return null;
        }
        String name = null;
        String value = null;
        String unit = null;
        String reference = null;
        BigDecimal minConfidence = null;
        for (int i = 0; i < row.size(); i++) {
            JsonNode cell = row.get(i);
            String key = text(cell.get("word_name"));
            String word = text(cell.get("word"));
            if (key == null || word == null) {
                continue;
            }
            boolean core = true;
            if (name == null && NAME_KEYS.contains(key)) {
                name = word;
            } else if (value == null && VALUE_KEYS.contains(key)) {
                value = word;
            } else if (unit == null && UNIT_KEYS.contains(key)) {
                unit = word;
            } else if (reference == null && REFERENCE_KEYS.contains(key)) {
                reference = word;
            } else {
                core = false;
            }
            if (core) {
                BigDecimal c = probability(cell.get("probability"));
                if (c != null && (minConfidence == null || c.compareTo(minConfidence) < 0)) {
                    minConfidence = c;
                }
            }
        }
        // 没有项目名或结果的行（表头、备注）丢弃：草稿只保留可确认的检验项目
        if (name == null || value == null) {
            return null;
        }
        return new OcrItem(name, value, unit, reference, sourcePhotoId, minConfidence);
    }

    /** 行置信度取名称/结果/单位/参考区间中最低者，保守提示用户核对。 */
    static BigDecimal probability(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode n = node.isObject() ? firstPresent(node.get("average"), node.get("min")) : node;
        if (n == null || n.isNull()) {
            return null;
        }
        BigDecimal value;
        if (n.isNumber()) {
            value = n.decimalValue();
        } else {
            String s = text(n);
            if (s == null) {
                return null;
            }
            try {
                value = new BigDecimal(s);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            return null;
        }
        return value.setScale(5, RoundingMode.HALF_UP);
    }

    static LocalDate parseDate(String text) {
        Matcher m = DATE.matcher(text);
        if (!m.find()) {
            return null;
        }
        try {
            return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)));
        } catch (DateTimeException ex) {
            return null;
        }
    }

    private static int datePriority(String key) {
        if (!(key.contains("日期") || key.contains("时间"))) {
            return Integer.MAX_VALUE;
        }
        for (int i = 0; i < DATE_KEY_PRIORITY.size(); i++) {
            if (key.contains(DATE_KEY_PRIORITY.get(i))) {
                return i;
            }
        }
        return DATE_KEY_PRIORITY.size();
    }

    private static JsonNode firstPresent(JsonNode a, JsonNode b) {
        return a != null && !a.isNull() ? a : b;
    }

    /** 宽容取文本：字符串或数字节点，去首尾空白；空串与 "null" 视为缺失。 */
    static String textOf(JsonNode node) {
        return text(node);
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String s = node.isString() ? node.stringValue() : node.isNumber() ? node.asString() : null;
        if (s == null) {
            return null;
        }
        s = s.strip();
        return s.isEmpty() ? null : s.toLowerCase(Locale.ROOT).equals("null") ? null : s;
    }

    private static List<String> nonNull(String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }
}
