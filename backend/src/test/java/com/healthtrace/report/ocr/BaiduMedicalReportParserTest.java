package com.healthtrace.report.ocr;

import com.healthtrace.report.ocr.BaiduMedicalReportParser.PageResult;
import com.healthtrace.report.ocr.OcrModels.OcrItem;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BaiduMedicalReportParserTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    /** 合成数据，结构参照百度医疗检验报告单识别的 words_result。 */
    private static final String SAMPLE = """
            {
              "CommonData": [
                {"word_name": "姓名", "word": "测试"},
                {"word_name": "报告日期", "word": "2026-10-21 15:30"},
                {"word_name": "采样时间", "word": "2026年10月20日 08:10"}
              ],
              "Item": [
                [
                  {"word_name": "项目名称", "word": "白细胞计数", "probability": {"average": 0.99, "min": 0.97}},
                  {"word_name": "结果", "word": "6.5", "probability": {"average": 0.75, "min": 0.6}},
                  {"word_name": "单位", "word": "10^9/L", "probability": 0.98},
                  {"word_name": "参考区间", "word": "3.5-9.5"}
                ],
                [
                  {"word_name": "项目名称", "word": "血红蛋白"},
                  {"word_name": "结果", "word": "120"},
                  {"word_name": "单位", "word": "g/L"},
                  {"word_name": "参考区间", "word": "115-150"}
                ],
                [
                  {"word_name": "备注", "word": "仅供参考"}
                ],
                [
                  {"word_name": "检验项目", "word": "尿蛋白"},
                  {"word_name": "检验结果", "word": "阴性"},
                  {"word_name": "参考值", "word": ""}
                ]
              ]
            }
            """;

    @Test
    void parsesItemsDateAndConfidence() {
        JsonNode words = mapper.readTree(SAMPLE);
        PageResult page = BaiduMedicalReportParser.parse(words, 7L);

        assertThat(page.items()).hasSize(3);
        OcrItem wbc = page.items().get(0);
        assertThat(wbc.name()).isEqualTo("白细胞计数");
        assertThat(wbc.valueText()).isEqualTo("6.5");
        assertThat(wbc.unitText()).isEqualTo("10^9/L");
        assertThat(wbc.referenceText()).isEqualTo("3.5-9.5");
        assertThat(wbc.sourcePhotoId()).isEqualTo(7L);
        // 行置信度取核心字段 average 的最低值，不伪造
        assertThat(wbc.confidence()).isEqualByComparingTo("0.75");

        OcrItem hgb = page.items().get(1);
        assertThat(hgb.confidence()).as("供应商未返回 probability 时为 null").isNull();

        OcrItem urine = page.items().get(2);
        assertThat(urine.name()).isEqualTo("尿蛋白");
        assertThat(urine.valueText()).isEqualTo("阴性");
        assertThat(urine.referenceText()).isNull();

        // 采样时间优先于报告日期
        assertThat(page.examinationDate()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(page.textLines()).contains("姓名：测试", "白细胞计数 6.5 10^9/L 3.5-9.5");
    }

    @Test
    void toleratesMissingSections() {
        PageResult page = BaiduMedicalReportParser.parse(mapper.readTree("{}"), 1L);
        assertThat(page.items()).isEmpty();
        assertThat(page.examinationDate()).isNull();

        PageResult nullPage = BaiduMedicalReportParser.parse(null, 1L);
        assertThat(nullPage.items()).isEmpty();
    }

    @Test
    void probabilityOutOfRangeOrInvalidIsNull() {
        assertThat(BaiduMedicalReportParser.probability(mapper.readTree("1.5"))).isNull();
        assertThat(BaiduMedicalReportParser.probability(mapper.readTree("-0.1"))).isNull();
        assertThat(BaiduMedicalReportParser.probability(mapper.readTree("\"abc\""))).isNull();
        assertThat(BaiduMedicalReportParser.probability(mapper.readTree("\"0.91234567\"")))
                .isEqualTo(new BigDecimal("0.91235"));
        assertThat(BaiduMedicalReportParser.probability(mapper.readTree("{\"min\": 0.5}")))
                .isEqualByComparingTo("0.5");
    }

    @Test
    void parsesDateVariants() {
        assertThat(BaiduMedicalReportParser.parseDate("2026/1/5")).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(BaiduMedicalReportParser.parseDate("2026.12.31 23:59")).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(BaiduMedicalReportParser.parseDate("2026-02-30")).isNull();
        assertThat(BaiduMedicalReportParser.parseDate("无")).isNull();
    }
}
