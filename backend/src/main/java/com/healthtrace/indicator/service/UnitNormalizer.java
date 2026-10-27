package com.healthtrace.indicator.service;

/**
 * 单位“写法”归一（FR-010）：只统一书写差异，绝不换算。
 * <ul>
 *   <li>去除半角/全角空白；全角 ％ ／ 转半角。</li>
 *   <li>上标数字转 ^n：×10⁹/L → 10^9/L；乘号 × / x / X / * 紧邻 10 时去掉。</li>
 *   <li>μ（U+03BC）、µ（U+00B5）统一为 u；结尾的升 l 统一为 L（mmol/l ≡ mmol/L，dl ≡ dL）。</li>
 * </ul>
 * g/L 与 g/dL、mmol/L 与 mg/dL 之类真正不同的单位保持不同，因而落入不同可比组。
 */
public final class UnitNormalizer {

    private static final String SUPERSCRIPTS = "⁰¹²³⁴⁵⁶⁷⁸⁹";

    private UnitNormalizer() {
    }

    /** 空或空白返回空串，表示“未注明单位”。 */
    public static String normalize(String unit) {
        if (unit == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(unit.length() + 4);
        boolean inSuperscript = false;
        for (int i = 0; i < unit.length(); i++) {
            char c = unit.charAt(i);
            if (Character.isWhitespace(c) || c == '\u3000' || c == '\u00A0') {
                continue;
            }
            int sup = SUPERSCRIPTS.indexOf(c);
            if (sup >= 0) {
                if (!inSuperscript) {
                    sb.append('^');
                    inSuperscript = true;
                }
                sb.append((char) ('0' + sup));
                continue;
            }
            inSuperscript = false;
            switch (c) {
                case '％' -> sb.append('%');
                case '／' -> sb.append('/');
                case 'μ', 'µ' -> sb.append('u');
                default -> sb.append(c);
            }
        }
        String s = sb.toString();
        // 乘号前缀：×10^9/L、x10^9/L、*10^9/L → 10^9/L
        s = s.replaceFirst("^[×xX*]+(?=10)", "");
        // 10E9/L 之类不处理，保持原样以免误并
        // 升：结尾或斜杠后紧跟的单独 l/dl/ml 统一大小写
        s = s.replaceAll("(?<=/|^|[dmu])l$", "L");
        return s;
    }

    /** 标本、方法文字只去空白，空返回空串。 */
    public static String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\\s\\u3000\\u00A0]+", "");
    }
}
