-- V5：MVP 18 项标准指标字典与别名（docs/31 §2，DEC-001），并按别名回填历史正式结果。
-- 只做“命名”映射，不换算数值、不改单位；未命中的名称保持 metric_definition_id 为空。

CREATE TABLE metric_definition
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    code               VARCHAR(40)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dictionary_id      VARCHAR(20)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    national_code      VARCHAR(20)  CHARACTER SET ascii COLLATE ascii_bin NULL,
    standard_name      VARCHAR(120) NOT NULL,
    standard_unit      VARCHAR(80)  NULL,
    report_type        ENUM ('BLOOD_ROUTINE', 'LIVER', 'KIDNEY', 'OTHER') NOT NULL,
    comparability_note TEXT         NULL,
    dictionary_version VARCHAR(40)  NOT NULL,
    sort_order         INT          NOT NULL,
    created_at         DATETIME     NOT NULL,
    updated_at         DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_metric_definition_code (code),
    UNIQUE KEY uk_metric_definition_dictionary_id (dictionary_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- alias_normalized = UPPER(去除半角/全角空格后的别名)，二进制排序保证精确匹配。
-- required_unit 非空时，只有结果单位（去空格、全角％转半角）等于该值才映射，例如单独的 “NEUT” 仅在单位为 % 时视为百分比。
CREATE TABLE metric_alias
(
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    metric_definition_id BIGINT       NOT NULL,
    alias                VARCHAR(160) NOT NULL,
    alias_normalized     VARCHAR(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    required_unit        VARCHAR(20)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_metric_alias_normalized (alias_normalized),
    CONSTRAINT fk_metric_alias_definition FOREIGN KEY (metric_definition_id) REFERENCES metric_definition (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO metric_definition (code, dictionary_id, national_code, standard_name, standard_unit, report_type,
                               comparability_note, dictionary_version, sort_order, created_at, updated_at)
VALUES ('WBC', 'MET-001', '0100101A', '白细胞计数', '×10⁹/L', 'BLOOD_ROUTINE', '仪器与校准差异会影响趋势', 'DICT-31-v1.0', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
       ('RBC', 'MET-002', '0100201A', '红细胞计数', '×10¹²/L', 'BLOOD_ROUTINE', '与性别相关；更换检测系统后不直接连线', 'DICT-31-v1.0', 2, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
       ('HGB', 'MET-003', '0100401A', '血红蛋白测定', 'g/L', 'BLOOD_ROUTINE', 'g/L 与 g/dL 相差 10 倍，系统不做换算', 'DICT-31-v1.0', 3, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
       ('PLT', 'MET-004', '0100301A', '血小板计数', '×10⁹/L', 'BLOOD_ROUTINE', '需警惕 EDTA 依赖性假性减低', 'DICT-31-v1.0', 4, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
       ('NEUT_PCT', 'MET-005', NULL, '中性粒细胞百分比', '%', 'BLOOD_ROUTINE', '与中性粒细胞绝对值（NEUT#）量纲不同，不能互推', 'DICT-31-v1.0', 5, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
       ('LYMPH_PCT', 'MET-006', NULL, '淋巴细胞百分比', '%', 'BLOOD_ROUTINE', '与淋巴细胞绝对值（LYMPH#）量纲不同，不能互推', 'DICT-31-v1.0', 6, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
       ('MONO_PCT', 'MET-007', NULL, '单核细胞百分比', '%', 'BLOOD_ROUTINE', '与单核细胞绝对值（MONO#）量纲不同，不能互推', 'DICT-31-v1.0', 7, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
       ('HCT', 'MET-008', '0100501A', '血细胞比容测定', '%', 'BLOOD_ROUTINE', '“压积”为别名', 'DICT-31-v1.0', 8, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
       ('MCV', 'MET-009', '0100601A', '平均红细胞体积测定', 'fL', 'BLOOD_ROUTINE', '与检测通道相关，换仪器不直接连线', 'DICT-31-v1.0', 9, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
       ('ALT', 'MET-010', '1500104A', '丙氨酸氨基转移酶测定', 'U/L', 'LIVER', '参考区间分含/不含 5′-磷酸吡哆醛两套方法', 'DICT-31-v1.0', 10, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
       ('AST', 'MET-011', '1500204A', '天门冬氨酸氨基转移酶测定', 'U/L', 'LIVER', '参考区间分含/不含 5′-磷酸吡哆醛两套方法', 'DICT-31-v1.0', 11, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
       ('TBIL', 'MET-012', '1700504A', '总胆红素测定', 'μmol/L', 'LIVER', '重氮法与氧化酶法结果不同，跨方法不直接连线', 'DICT-31-v1.0', 12, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
       ('DBIL', 'MET-013', '1700604A', '直接胆红素测定', 'μmol/L', 'LIVER', '重氮法与氧化酶法结果不同，跨方法不直接连线', 'DICT-31-v1.0', 13, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
       ('TP', 'MET-014', '1100204A', '总蛋白测定', 'g/L', 'LIVER', '以双缩脲法为主', 'DICT-31-v1.0', 14, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
       ('ALB', 'MET-015', '1100104A', '白蛋白测定', 'g/L', 'LIVER', '溴甲酚绿法与免疫比浊法结果不同', 'DICT-31-v1.0', 15, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
       ('CREA', 'MET-016', '1700404A', '肌酐测定', 'μmol/L', 'KIDNEY', '酶法与苦味酸法结果及参考区间不同，跨方法不直接连线', 'DICT-31-v1.0', 16, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
       ('UREA', 'MET-017', '1700204A', '尿素测定', 'mmol/L', 'KIDNEY', '“尿素氮”数值尺度不同，作为独立项目，不归入本指标', 'DICT-31-v1.0', 17, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
       ('UA', 'MET-018', '1700304A', '尿酸测定', 'μmol/L', 'KIDNEY', '酶法与磷钨酸法有差异；与性别相关', 'DICT-31-v1.0', 18, UTC_TIMESTAMP(), UTC_TIMESTAMP());

-- 别名：标准名、去掉“测定”的常用名、docs/31 列出的别名与简称。
-- 刻意不收录：尿素氮/血尿素氮/BUN（数值尺度不同，见 docs/31 MET-017）。
INSERT INTO metric_alias (metric_definition_id, alias, alias_normalized, required_unit)
SELECT d.id, a.alias, UPPER(a.alias), a.required_unit
FROM metric_definition d
         JOIN (SELECT 'WBC' code, '白细胞计数' alias, NULL required_unit
               UNION ALL SELECT 'WBC', '白细胞', NULL
               UNION ALL SELECT 'WBC', 'WBC', NULL
               UNION ALL SELECT 'WBC', 'WBC#', NULL
               UNION ALL SELECT 'WBC', 'LEU', NULL
               UNION ALL SELECT 'RBC', '红细胞计数', NULL
               UNION ALL SELECT 'RBC', '红细胞', NULL
               UNION ALL SELECT 'RBC', 'RBC', NULL
               UNION ALL SELECT 'RBC', 'RBC#', NULL
               UNION ALL SELECT 'HGB', '血红蛋白测定', NULL
               UNION ALL SELECT 'HGB', '血红蛋白', NULL
               UNION ALL SELECT 'HGB', '血色素', NULL
               UNION ALL SELECT 'HGB', 'HGB', NULL
               UNION ALL SELECT 'HGB', 'Hb', NULL
               UNION ALL SELECT 'PLT', '血小板计数', NULL
               UNION ALL SELECT 'PLT', '血小板', NULL
               UNION ALL SELECT 'PLT', 'PLT', NULL
               UNION ALL SELECT 'NEUT_PCT', '中性粒细胞百分比', NULL
               UNION ALL SELECT 'NEUT_PCT', '中性粒百分比', NULL
               UNION ALL SELECT 'NEUT_PCT', '中性%', NULL
               UNION ALL SELECT 'NEUT_PCT', 'NEUT%', NULL
               UNION ALL SELECT 'NEUT_PCT', 'NEUT', '%'
               UNION ALL SELECT 'LYMPH_PCT', '淋巴细胞百分比', NULL
               UNION ALL SELECT 'LYMPH_PCT', '淋巴百分比', NULL
               UNION ALL SELECT 'LYMPH_PCT', 'LYMPH%', NULL
               UNION ALL SELECT 'LYMPH_PCT', 'LYM%', NULL
               UNION ALL SELECT 'MONO_PCT', '单核细胞百分比', NULL
               UNION ALL SELECT 'MONO_PCT', '单核百分比', NULL
               UNION ALL SELECT 'MONO_PCT', 'MONO%', NULL
               UNION ALL SELECT 'MONO_PCT', 'MON%', NULL
               UNION ALL SELECT 'HCT', '血细胞比容测定', NULL
               UNION ALL SELECT 'HCT', '血细胞比容', NULL
               UNION ALL SELECT 'HCT', '红细胞压积', NULL
               UNION ALL SELECT 'HCT', '压积', NULL
               UNION ALL SELECT 'HCT', 'HCT', NULL
               UNION ALL SELECT 'HCT', 'PCV', NULL
               UNION ALL SELECT 'MCV', '平均红细胞体积测定', NULL
               UNION ALL SELECT 'MCV', '平均红细胞体积', NULL
               UNION ALL SELECT 'MCV', '平均红细胞容积', NULL
               UNION ALL SELECT 'MCV', 'MCV', NULL
               UNION ALL SELECT 'ALT', '丙氨酸氨基转移酶测定', NULL
               UNION ALL SELECT 'ALT', '丙氨酸氨基转移酶', NULL
               UNION ALL SELECT 'ALT', '谷丙转氨酶', NULL
               UNION ALL SELECT 'ALT', 'ALT', NULL
               UNION ALL SELECT 'ALT', 'GPT', NULL
               UNION ALL SELECT 'AST', '天门冬氨酸氨基转移酶测定', NULL
               UNION ALL SELECT 'AST', '天门冬氨酸氨基转移酶', NULL
               UNION ALL SELECT 'AST', '天冬氨酸氨基转移酶', NULL
               UNION ALL SELECT 'AST', '谷草转氨酶', NULL
               UNION ALL SELECT 'AST', 'AST', NULL
               UNION ALL SELECT 'AST', 'GOT', NULL
               UNION ALL SELECT 'TBIL', '总胆红素测定', NULL
               UNION ALL SELECT 'TBIL', '总胆红素', NULL
               UNION ALL SELECT 'TBIL', '总胆', NULL
               UNION ALL SELECT 'TBIL', 'TBIL', NULL
               UNION ALL SELECT 'TBIL', 'T-BIL', NULL
               UNION ALL SELECT 'DBIL', '直接胆红素测定', NULL
               UNION ALL SELECT 'DBIL', '直接胆红素', NULL
               UNION ALL SELECT 'DBIL', '结合胆红素', NULL
               UNION ALL SELECT 'DBIL', 'DBIL', NULL
               UNION ALL SELECT 'DBIL', 'D-BIL', NULL
               UNION ALL SELECT 'TP', '总蛋白测定', NULL
               UNION ALL SELECT 'TP', '总蛋白', NULL
               UNION ALL SELECT 'TP', '血清总蛋白', NULL
               UNION ALL SELECT 'TP', 'TP', NULL
               UNION ALL SELECT 'ALB', '白蛋白测定', NULL
               UNION ALL SELECT 'ALB', '白蛋白', NULL
               UNION ALL SELECT 'ALB', '血清白蛋白', NULL
               UNION ALL SELECT 'ALB', '清蛋白', NULL
               UNION ALL SELECT 'ALB', 'ALB', NULL
               UNION ALL SELECT 'CREA', '肌酐测定', NULL
               UNION ALL SELECT 'CREA', '肌酐', NULL
               UNION ALL SELECT 'CREA', '血肌酐', NULL
               UNION ALL SELECT 'CREA', 'Cr', NULL
               UNION ALL SELECT 'CREA', 'CREA', NULL
               UNION ALL SELECT 'CREA', 'Scr', NULL
               UNION ALL SELECT 'UREA', '尿素测定', NULL
               UNION ALL SELECT 'UREA', '尿素', NULL
               UNION ALL SELECT 'UREA', 'UREA', NULL
               UNION ALL SELECT 'UA', '尿酸测定', NULL
               UNION ALL SELECT 'UA', '尿酸', NULL
               UNION ALL SELECT 'UA', '血尿酸', NULL
               UNION ALL SELECT 'UA', 'UA', NULL) a ON a.code = d.code;

-- 回填：历史正式结果按同一规则映射（与 ObservationMapper.assignMetricDefinitions 保持一致）。
UPDATE observation o
    JOIN metric_alias a
    ON a.alias_normalized =
       UPPER(REPLACE(REPLACE(REPLACE(TRIM(o.original_name), ' ', ''), '　', ''), '％', '%')) COLLATE utf8mb4_bin
SET o.metric_definition_id = a.metric_definition_id
WHERE o.status = 'FORMAL'
  AND o.metric_definition_id IS NULL
  AND o.temp_metric_id IS NULL
  AND (a.required_unit IS NULL
    OR REPLACE(REPLACE(TRIM(IFNULL(o.unit_text, '')), ' ', ''), '％', '%') COLLATE utf8mb4_bin = a.required_unit);

CREATE INDEX idx_observation_metric ON observation (metric_definition_id, status, examination_date);
