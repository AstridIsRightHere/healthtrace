package com.healthtrace.integration;

import com.healthtrace.account.service.AccountMailSender;
import com.healthtrace.indicator.domain.FollowPolicy;
import com.healthtrace.indicator.domain.FollowedMetric;
import com.healthtrace.indicator.domain.MetricDefinition;
import com.healthtrace.indicator.domain.TrendObservation;
import com.healthtrace.indicator.mapper.FollowedMetricMapper;
import com.healthtrace.indicator.mapper.TrendMapper;
import com.healthtrace.indicator.service.TrendService;
import com.healthtrace.indicator.service.TrendViews.MetricLatestSummaries;
import com.healthtrace.indicator.service.TrendViews.MetricLatestSummary;
import com.healthtrace.indicator.service.TrendViews.MetricSummary;
import com.healthtrace.indicator.service.TrendViews.TrendView;
import com.healthtrace.profile.domain.Relationship;
import com.healthtrace.profile.service.ProfileService;
import com.healthtrace.profile.service.ProfileView;
import com.healthtrace.report.domain.AbnormalFlag;
import com.healthtrace.report.domain.Observation;
import com.healthtrace.report.domain.ObservationStatus;
import com.healthtrace.report.domain.ResultKind;
import com.healthtrace.report.mapper.ObservationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 MySQL 8.4 集成测试（ADR-012）：验证 Flyway 全部迁移可在 MySQL 上执行，
 * 并实际运行 MyBatis XML 语句（JOIN 过滤、别名映射、方言函数），这些在 @WebMvcTest/Mockito 中无法覆盖。
 * 需要 Docker；CI（ubuntu-latest）自带 Docker，随 mvn verify 执行。
 */
// spring.profiles.active 在环境准备阶段即解析，@DynamicPropertySource 太晚，须用 properties 提供占位符
@SpringBootTest(properties = "SPRING_PROFILES_ACTIVE=test")
@Testcontainers
class MysqlIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("healthtrace.security.email-encryption-key",
                () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        registry.add("healthtrace.security.email-index-key",
                () -> "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=");
        registry.add("healthtrace.storage.local.root", () -> storageRoot.toString());
        registry.add("healthtrace.ocr.encryption-key", () -> "YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=");
        registry.add("healthtrace.ocr.baidu.api-key", () -> "test-api-key");
        registry.add("healthtrace.ocr.baidu.secret-key", () -> "test-secret-key");
    }

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 1, 8, 0);
    private static final LocalDate EXAM_DATE = LocalDate.of(2026, 2, 20);

    // 非 dev 环境按配置选用 SMTP 或未配置实现；集成测试不发真实邮件，以 mock 替换，不影响数据库验证
    @MockitoBean
    private AccountMailSender accountMailSender;
    @Autowired
    private TrendService trendService;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TrendMapper trendMapper;
    @Autowired
    private ObservationMapper observationMapper;
    @Autowired
    private FollowedMetricMapper followedMetricMapper;
    @Autowired
    private ProfileService profileService;

    @Test
    void flywayAppliesEveryMigrationOnClasspath() throws IOException {
        int files = new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/V*__*.sql").length;
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL",
                Integer.class);
        Integer failed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0", Integer.class);

        assertThat(files).isGreaterThanOrEqualTo(8);
        assertThat(applied).isEqualTo(files);
        assertThat(failed).isZero();
    }

    @Test
    void metricDictionaryQueries() {
        List<MetricDefinition> all = trendMapper.findAllDefinitions();
        assertThat(all).hasSize(18);
        assertThat(all.getFirst().getCode()).isEqualTo("WBC");
        assertThat(all).extracting(MetricDefinition::getSortOrder).isSorted();

        MetricDefinition alt = trendMapper.findDefinitionByCode("ALT");
        assertThat(alt).isNotNull();
        assertThat(alt.getSortOrder()).isEqualTo(10);
        assertThat(alt.getStandardUnit()).isEqualTo("U/L");
        assertThat(trendMapper.findDefinitionByCode("NOPE")).isNull();
    }

    @Test
    void observationDraftLifecycleAndAliasMapping() {
        long profileId = seedProfile();
        long reportId = seedReport(profileId, "PENDING_CONFIRMATION", "NORMAL");

        // 全角空格、小写、单位约束：均须与 V5 回填规则一致
        Observation wbc = draft(reportId, " 白细胞 ", "6.5", new BigDecimal("6.5"), "10^9/L", 0);
        Observation alt = draft(reportId, "gpt", "35", new BigDecimal("35"), "U/L", 1);
        Observation neutPct = draft(reportId, "NEUT", "60", new BigDecimal("60"), "％", 2);
        Observation neutAbs = draft(reportId, "NEUT", "3.1", new BigDecimal("3.1"), "10^9/L", 3);
        Observation unknown = draft(reportId, "某罕见项目", "阴性", null, null, 4);
        Observation removed = draft(reportId, "临时行", "1", BigDecimal.ONE, null, 5);
        for (Observation o : List.of(wbc, alt, neutPct, neutAbs, unknown, removed)) {
            assertThat(observationMapper.insert(o)).isEqualTo(1);
            assertThat(o.getId()).isNotNull();
        }

        // 草稿编辑 / 删除
        alt.setOriginalValue("40");
        alt.setNumericValue(new BigDecimal("40"));
        alt.setAbnormalFlag(AbnormalFlag.HIGH);
        alt.setUpdatedAt(NOW.plusMinutes(1));
        assertThat(observationMapper.updateDraft(alt)).isEqualTo(1);
        assertThat(observationMapper.deleteDraftById(reportId, removed.getId())).isEqualTo(1);
        // 其他报告的 id 不能删除本报告草稿
        assertThat(observationMapper.deleteDraftById(reportId + 999, wbc.getId())).isZero();

        List<Observation> drafts = observationMapper.findByReport(reportId, ObservationStatus.DRAFT);
        assertThat(drafts).extracting(Observation::getOriginalName)
                .containsExactly(" 白细胞 ", "gpt", "NEUT", "NEUT", "某罕见项目");
        assertThat(drafts.get(1).getOriginalValue()).isEqualTo("40");
        assertThat(drafts.get(1).getAbnormalFlag()).isEqualTo(AbnormalFlag.HIGH);
        assertThat(observationMapper.findByReport(reportId, ObservationStatus.FORMAL)).isEmpty();

        // 确认前草稿不参与映射
        assertThat(observationMapper.assignMetricDefinitions(reportId)).isZero();

        // 确认：草稿转正式并写入统一检查日期
        assertThat(observationMapper.confirmDrafts(reportId, EXAM_DATE, NOW.plusHours(1))).isEqualTo(5);
        markConfirmed(reportId);
        List<Observation> formal = observationMapper.findByReport(reportId, ObservationStatus.FORMAL);
        assertThat(formal).hasSize(5).allSatisfy(o -> {
            assertThat(o.getExaminationDate()).isEqualTo(EXAM_DATE);
            assertThat(o.getConfirmedAt()).isEqualTo(NOW.plusHours(1));
        });
        assertThat(observationMapper.findByReport(reportId, ObservationStatus.DRAFT)).isEmpty();
        assertThat(observationMapper.updateDraft(alt)).isZero();
        assertThat(observationMapper.deleteDrafts(reportId)).isZero();

        // 别名映射：WBC、ALT、NEUT(单位 %) 命中；NEUT(非 %) 与未知名称保持未映射
        assertThat(observationMapper.assignMetricDefinitions(reportId)).isEqualTo(3);
        assertThat(metricCodeOf(wbc.getId())).isEqualTo("WBC");
        assertThat(metricCodeOf(alt.getId())).isEqualTo("ALT");
        assertThat(metricCodeOf(neutPct.getId())).isEqualTo("NEUT_PCT");
        assertThat(metricCodeOf(neutAbs.getId())).isNull();
        assertThat(metricCodeOf(unknown.getId())).isNull();

        // findFormalByReport：按 id 升序，带 metricCode
        List<TrendObservation> byReport = trendMapper.findFormalByReport(reportId);
        assertThat(byReport).extracting(TrendObservation::getObservationId)
                .containsExactly(wbc.getId(), alt.getId(), neutPct.getId(), neutAbs.getId(), unknown.getId());
        assertThat(byReport).extracting(TrendObservation::getMetricCode)
                .containsExactly("WBC", "ALT", "NEUT_PCT", null, null);

        // FR-014 修订（SUPERSEDED/RETRACTED）路径见 ObservationRevisionIntegrationTest

        // 未确认报告的彻底删除路径
        long other = seedReport(profileId, "PENDING_CONFIRMATION", "NORMAL");
        observationMapper.insert(draft(other, "PLT", "200", new BigDecimal("200"), null, 0));
        observationMapper.insert(draft(other, "HGB", "130", new BigDecimal("130"), null, 1));
        assertThat(observationMapper.deleteDrafts(other)).isEqualTo(2);
        observationMapper.insert(draft(other, "PLT", "200", new BigDecimal("200"), null, 0));
        assertThat(observationMapper.deleteByReport(other)).isEqualTo(1);
    }

    @Test
    void trendQueriesOnlySeeConfirmedVisibleFormalRows() {
        long profileId = seedProfile();
        long otherProfile = seedProfile();
        long visible = seedReport(profileId, "PENDING_CONFIRMATION", "NORMAL");
        long earlier = seedReport(profileId, "PENDING_CONFIRMATION", "NORMAL");
        long removed = seedReport(profileId, "PENDING_CONFIRMATION", "NORMAL");
        long pending = seedReport(profileId, "PENDING_CONFIRMATION", "NORMAL");
        long foreign = seedReport(otherProfile, "PENDING_CONFIRMATION", "NORMAL");

        observationMapper.insert(draft(visible, "ALT", "40", new BigDecimal("40"), "U/L", 0));
        observationMapper.insert(draft(visible, "某罕见项目", "阴性", null, null, 1));
        observationMapper.insert(draft(earlier, "谷丙转氨酶", "30", new BigDecimal("30"), "U/L", 0));
        observationMapper.insert(draft(removed, "ALT", "99", new BigDecimal("99"), "U/L", 0));
        observationMapper.insert(draft(pending, "ALT", "77", new BigDecimal("77"), "U/L", 0));
        observationMapper.insert(draft(foreign, "ALT", "55", new BigDecimal("55"), "U/L", 0));

        confirm(visible, EXAM_DATE);
        confirm(earlier, EXAM_DATE.minusMonths(3));
        confirm(removed, EXAM_DATE.minusMonths(1));
        jdbc.update("UPDATE report SET visibility_status = 'LIST_REMOVED' WHERE id = ?", removed);
        confirm(foreign, EXAM_DATE);
        // pending 保持草稿与 PENDING_CONFIRMATION，不应进入任何趋势

        List<TrendObservation> all = trendMapper.findFormalByProfile(profileId);
        // 排序：标准指标 sort_order → 原名 → 检查日期；未映射行排在最后
        assertThat(all).extracting(TrendObservation::getOriginalValue).containsExactly("40", "30", "阴性");
        assertThat(all).extracting(TrendObservation::getMetricCode).containsExactly("ALT", "ALT", null);

        long altId = trendMapper.findDefinitionByCode("ALT").getId();
        LocalDate longAgo = EXAM_DATE.minusYears(5);
        assertThat(trendMapper.findFormalByMetric(profileId, altId, longAgo, EXAM_DATE))
                .extracting(TrendObservation::getNumericValue)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("30"), new BigDecimal("40"));
        // to 为闭区间上界
        assertThat(trendMapper.findFormalByMetric(profileId, altId, longAgo, EXAM_DATE.minusDays(1)))
                .extracting(TrendObservation::getReportId).containsExactly(earlier);
        // from 为闭区间下界，由 SQL 过滤
        assertThat(trendMapper.findFormalByMetric(profileId, altId, EXAM_DATE, EXAM_DATE))
                .extracting(TrendObservation::getReportId).containsExactly(visible);
        assertThat(trendMapper.findFormalByMetric(profileId, altId, EXAM_DATE.minusDays(1), EXAM_DATE))
                .extracting(TrendObservation::getReportId).containsExactly(visible);

        assertThat(trendMapper.findFormalUnmapped(profileId, EXAM_DATE))
                .extracting(TrendObservation::getOriginalName).containsExactly("某罕见项目");
        assertThat(trendMapper.findFormalUnmapped(profileId, EXAM_DATE.minusDays(1))).isEmpty();
    }

    @Test
    void followedMetricReplaceAndRead() {
        long profileId = seedProfile();
        // V8 只为迁移时已存在的档案回填默认关注项；新档案从空开始
        assertThat(followedMetricMapper.findByProfile(profileId)).isEmpty();

        assertThat(followedMetricMapper.insertAll(profileId, List.of("CREA", "WBC", "ALT"), NOW)).isEqualTo(3);
        List<FollowedMetric> followed = followedMetricMapper.findByProfile(profileId);
        assertThat(followed).extracting(FollowedMetric::getMetricCode).containsExactly("CREA", "WBC", "ALT");
        assertThat(followed).extracting(FollowedMetric::getSortOrder).containsExactly(0, 1, 2);
        assertThat(followed.get(1).getStandardName()).isEqualTo("白细胞计数");

        assertThat(followedMetricMapper.deleteByProfile(profileId)).isEqualTo(3);
        assertThat(followedMetricMapper.findByProfile(profileId)).isEmpty();
    }

    @Test
    void createProfileSeedsDefaultFollowedMetrics() {
        long accountId = seedAccount();
        ProfileView view = profileService.createProfile(accountId, Relationship.SELF, "建档测试", true);

        // 与档案同事务写入 PRD 默认关注 6 项（FR-009），按列表顺序
        assertThat(followedMetricMapper.findByProfile(view.id()))
                .extracting(FollowedMetric::getMetricCode)
                .containsExactlyElementsOf(FollowPolicy.DEFAULT_CODES)
                .containsExactly("WBC", "HGB", "PLT", "ALT", "AST", "CREA");
    }

    @Test
    void trendHistoryAndLatestSummaryQueries() {
        long profileId = seedProfile();
        long r1 = seedReport(profileId, "PENDING_CONFIRMATION", "NORMAL");
        long r2 = seedReport(profileId, "PENDING_CONFIRMATION", "NORMAL");
        long r3 = seedReport(profileId, "PENDING_CONFIRMATION", "NORMAL");
        observationMapper.insert(draft(r1, "ALT", "30", new BigDecimal("30"), "U/L", 0));
        observationMapper.insert(draft(r1, " 白细胞 ", "5.0", new BigDecimal("5.0"), "10^9/L", 1));
        observationMapper.insert(draft(r2, "谷丙转氨酶", "40", new BigDecimal("40"), "U/L", 0));
        observationMapper.insert(draft(r3, "gpt", "50", new BigDecimal("50"), "U/L", 0));
        observationMapper.insert(draft(r3, " 白细胞 ", "6500", new BigDecimal("6500"), "/uL", 1));
        confirm(r1, EXAM_DATE.minusDays(60));
        confirm(r2, EXAM_DATE.minusDays(30));
        confirm(r3, EXAM_DATE);
        long altId = trendMapper.findDefinitionByCode("ALT").getId();

        // 窗口前历史：各原始写法最早一条 + 最近精确日期的全部行
        assertThat(trendMapper.findFormalHistoryByMetric(profileId, altId, EXAM_DATE))
                .extracting(TrendObservation::getReportId).containsExactly(r1, r2);
        assertThat(trendMapper.findFormalHistoryByMetric(profileId, altId, EXAM_DATE.minusDays(30)))
                .extracting(TrendObservation::getReportId).containsExactly(r1);
        assertThat(trendMapper.findFormalHistoryByMetric(profileId, altId, EXAM_DATE.minusDays(60))).isEmpty();

        // 最新值摘要：每个指标每种原始写法只取最近 2 个日期
        assertThat(trendMapper.findRecentExactByMetrics(profileId, List.of(altId), EXAM_DATE))
                .extracting(TrendObservation::getReportId).containsExactly(r2, r3);
        assertThat(trendMapper.findRecentExactByMetrics(profileId, List.of(altId), EXAM_DATE.minusDays(1)))
                .extracting(TrendObservation::getReportId).containsExactly(r1, r2);
    }

    @Test
    void trendServiceEndToEndMatchesFullComputation() {
        long profileId = seedProfile();
        long accountId = jdbc.queryForObject(
                "SELECT account_id FROM profile_access WHERE profile_id = ? LIMIT 1", Long.class, profileId);
        long r1 = seedReport(profileId, "PENDING_CONFIRMATION", "NORMAL");
        long r2 = seedReport(profileId, "PENDING_CONFIRMATION", "NORMAL");
        observationMapper.insert(draft(r1, "ALT", "40", new BigDecimal("40"), "U/L", 0));
        observationMapper.insert(draft(r1, " 白细胞 ", "5.0", new BigDecimal("5.0"), "10^9/L", 1));
        observationMapper.insert(draft(r2, "gpt", "50", new BigDecimal("50"), "U/L", 0));
        observationMapper.insert(draft(r2, " 白细胞 ", "6500", new BigDecimal("6500"), "/uL", 1));
        observationMapper.insert(draft(r2, "某罕见项目", "阴性", null, null, 2));
        confirm(r1, EXAM_DATE.minusDays(30));
        confirm(r2, EXAM_DATE);

        // 窗口首点的增减来自窗口前历史（SQL 下界之外的行）
        TrendView trend = trendService.getTrend(accountId, profileId, "ALT", "custom", EXAM_DATE, EXAM_DATE);
        assertThat(trend.groups()).singleElement().satisfies(g -> {
            assertThat(g.points()).singleElement().satisfies(p -> {
                assertThat(p.change().absolute()).isEqualByComparingTo("10");
                assertThat(p.change().percent()).isEqualByComparingTo("25.00");
            });
        });

        MetricLatestSummaries summaries = trendService.getLatestSummaries(accountId, profileId, "ALT,WBC,HGB");
        assertThat(summaries.items()).extracting(MetricLatestSummary::metricCode).containsExactly("ALT", "WBC", "HGB");
        assertThat(summaries.items().get(0).latest().numericValue()).isEqualByComparingTo("50");
        assertThat(summaries.items().get(0).change().absolute()).isEqualByComparingTo("10");
        // WBC 最新点在 /uL 组，组间不计算增减
        assertThat(summaries.items().get(1).latest().unitText()).isEqualTo("/uL");
        assertThat(summaries.items().get(1).change()).isNull();
        assertThat(summaries.items().get(2).latest()).isNull();

        // /metrics 聚合结果与逐行（findFormalByProfile）统计一致
        List<TrendObservation> rows = trendMapper.findFormalByProfile(profileId);
        assertThat(rows).hasSize(5);
        List<MetricSummary> metrics = trendService.listMetrics(accountId, profileId);
        assertThat(metrics).extracting(MetricSummary::code).containsExactly("WBC", "ALT", "RAW:某罕见项目");
        assertThat(metrics).extracting(MetricSummary::count).containsExactly(2, 2, 1);
        assertThat(metrics).extracting(MetricSummary::groupCount).containsExactly(2, 1, 1);
        assertThat(metrics).extracting(MetricSummary::latestDate).containsOnly(EXAM_DATE);
        assertThat(metrics).extracting(MetricSummary::standard).containsExactly(true, true, false);
    }

    // ---- 种子数据 ----

    private long seedAccount() {
        String email = UUID.randomUUID().toString();
        return insert("""
                INSERT INTO account (email_ciphertext, email_index, password_hash, status, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?)""",
                email.getBytes(), HexFormat.of().formatHex((email + email).getBytes()).substring(0, 64),
                "{noop}x", NOW, NOW);
    }

    private long seedProfile() {
        long accountId = seedAccount();
        long profileId = insert("""
                INSERT INTO patient_profile (nickname, status, created_at, updated_at)
                VALUES ('测试', 'ACTIVE', ?, ?)""", NOW, NOW);
        insert("""
                INSERT INTO profile_access (account_id, profile_id, relationship, access_status, created_at, updated_at)
                VALUES (?, ?, 'SELF', 'ACTIVE', ?, ?)""", accountId, profileId, NOW, NOW);
        return profileId;
    }

    private long seedReport(long profileId, String processing, String visibility) {
        Long account = jdbc.queryForObject(
                "SELECT account_id FROM profile_access WHERE profile_id = ? LIMIT 1", Long.class, profileId);
        return insert("""
                INSERT INTO report (profile_id, created_by, processing_status, visibility_status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)""", profileId, account, processing, visibility, NOW, NOW);
    }

    private void confirm(long reportId, LocalDate examinationDate) {
        observationMapper.confirmDrafts(reportId, examinationDate, NOW);
        markConfirmed(reportId);
        observationMapper.assignMetricDefinitions(reportId);
    }

    private void markConfirmed(long reportId) {
        jdbc.update("UPDATE report SET processing_status = 'CONFIRMED' WHERE id = ?", reportId);
    }

    private String metricCodeOf(long observationId) {
        return jdbc.queryForObject("""
                SELECT d.code FROM observation o LEFT JOIN metric_definition d ON d.id = o.metric_definition_id
                WHERE o.id = ?""", String.class, observationId);
    }

    private static Observation draft(long reportId, String name, String value, BigDecimal numeric,
                                     String unit, int rowOrder) {
        Observation o = new Observation();
        o.setReportId(reportId);
        o.setOriginalName(name);
        o.setOriginalValue(value);
        o.setResultKind(numeric == null ? ResultKind.QUALITATIVE : ResultKind.EXACT);
        o.setNumericValue(numeric);
        o.setUnitText(unit);
        o.setAbnormalFlag(AbnormalFlag.NORMAL);
        o.setStatus(ObservationStatus.DRAFT);
        o.setRowOrder(rowOrder);
        o.setCreatedAt(NOW);
        o.setUpdatedAt(NOW);
        return o;
    }

    private long insert(String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }
}
