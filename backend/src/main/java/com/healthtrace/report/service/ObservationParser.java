package com.healthtrace.report.service;

import com.healthtrace.report.domain.AbnormalFlag;
import com.healthtrace.report.domain.Observation;
import com.healthtrace.report.domain.ResultKind;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把报告单上的原始写法解析为结构化字段（docs/19 OBSERVATION）。
 * <ul>
 *   <li>只做文本层面的机械解析：不换算单位、不做医学判断；解析不了的保持 UNKNOWN，原文始终保留。</li>
 *   <li>异常标记只在“精确数值 + 参考上下限都已知”时按区间比较，否则为 UNKNOWN。</li>
 * </ul>
 */
public final class ObservationParser {

    /** 可选的比较符 + 数值；允许尾随 ↑↓ / H / L 等报告单标记。 */
    private static final Pattern VALUE = Pattern.compile(
            "^(<=|>=|≤|≥|<|>|＜|＞)?\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*(?:[↑↓]|[HhLl])?$");
    /** 参考范围 a-b、a~b、a～b、a—b。 */
    private static final Pattern RANGE = Pattern.compile(
            "^([+-]?\\d+(?:\\.\\d+)?)\\s*(?:-|~|～|—|–|－)\\s*([+-]?\\d+(?:\\.\\d+)?)$");
    /** 单侧参考范围 <b、≤b、>a、≥a。 */
    private static final Pattern ONE_SIDED = Pattern.compile("^(<=|>=|≤|≥|<|>|＜|＞)\\s*([+-]?\\d+(?:\\.\\d+)?)$");

    private ObservationParser() {
    }

    public record ParsedValue(ResultKind kind, BigDecimal numericValue) {
    }

    public record ParsedRange(BigDecimal low, BigDecimal high) {
        static final ParsedRange NONE = new ParsedRange(null, null);
    }

    public static ParsedValue parseValue(String originalValue) {
        if (originalValue == null || originalValue.isBlank()) {
            return new ParsedValue(ResultKind.UNKNOWN, null);
        }
        String v = originalValue.strip();
        Matcher m = VALUE.matcher(v);
        if (m.matches()) {
            BigDecimal number = new BigDecimal(m.group(2));
            String op = m.group(1);
            if (op == null) {
                return new ParsedValue(ResultKind.EXACT, number);
            }
            return new ParsedValue(isLess(op) ? ResultKind.LESS_THAN : ResultKind.GREATER_THAN, number);
        }
        // 含数字但无法解析（如 "6.5x"）视为未知；纯文字（阴性、+、弱阳性）视为定性
        return new ParsedValue(v.chars().anyMatch(Character::isDigit) ? ResultKind.UNKNOWN : ResultKind.QUALITATIVE,
                null);
    }

    public static ParsedRange parseRange(String referenceText) {
        if (referenceText == null || referenceText.isBlank()) {
            return ParsedRange.NONE;
        }
        String r = referenceText.strip();
        Matcher m = RANGE.matcher(r);
        if (m.matches()) {
            BigDecimal low = new BigDecimal(m.group(1));
            BigDecimal high = new BigDecimal(m.group(2));
            return low.compareTo(high) <= 0 ? new ParsedRange(low, high) : ParsedRange.NONE;
        }
        Matcher one = ONE_SIDED.matcher(r);
        if (one.matches()) {
            BigDecimal bound = new BigDecimal(one.group(2));
            return isLess(one.group(1)) ? new ParsedRange(null, bound) : new ParsedRange(bound, null);
        }
        return ParsedRange.NONE;
    }

    public static AbnormalFlag abnormalFlag(ParsedValue value, ParsedRange range) {
        if (value.kind() != ResultKind.EXACT || range.low() == null || range.high() == null) {
            return AbnormalFlag.UNKNOWN;
        }
        if (value.numericValue().compareTo(range.low()) < 0) {
            return AbnormalFlag.LOW;
        }
        if (value.numericValue().compareTo(range.high()) > 0) {
            return AbnormalFlag.HIGH;
        }
        return AbnormalFlag.NORMAL;
    }

    /** 按 originalValue / referenceText 重新推导结构化字段（结果类型、数值、上下限、异常标记）。 */
    public static void derive(Observation o) {
        ParsedValue value = parseValue(o.getOriginalValue());
        ParsedRange range = parseRange(o.getReferenceText());
        o.setResultKind(value.kind());
        o.setNumericValue(value.numericValue());
        o.setReferenceLow(range.low());
        o.setReferenceHigh(range.high());
        o.setAbnormalFlag(abnormalFlag(value, range));
    }

    private static boolean isLess(String op) {
        return op.startsWith("<") || op.equals("≤") || op.equals("＜");
    }
}
