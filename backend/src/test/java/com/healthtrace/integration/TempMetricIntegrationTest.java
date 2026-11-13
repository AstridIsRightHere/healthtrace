package com.healthtrace.integration;

import com.healthtrace.account.service.AccountMailSender;
import com.healthtrace.indicator.service.FollowService;
import com.healthtrace.indicator.service.FollowViews.FollowedMetricView;
import com.healthtrace.indicator.service.TempMetricService;
import com.healthtrace.indicator.service.TempMetricViews.TempMetricView;
import com.healthtrace.indicator.service.TrendService;
import com.healthtrace.indicator.service.TrendViews.MetricLatestSummaries;
import com.healthtrace.indicator.service.TrendViews.MetricSummary;
import com.healthtrace.indicator.service.TrendViews.TrendView;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

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
 * 临时指标（FR-009，DEC-015）真实 MySQL 集成测试：V13 迁移、同键自动归并、关注 TMP 编码、
 * 趋势 / 最新摘要 / 指标列表对 TMP 的支持、确认后自动归入、删除后回落为 RAW。需要 Docker。
 */
@SpringBootTest(properties = "SPRING_PROFILES_ACTIVE=test")
@Testcontainers
class TempMetricIntegrationTest {

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
        registry.add("healthtrace.storage.encryption-key", () -> "c3RvcmFnZS1maWxlLWtleS0wMTIzNDU2Nzg5YWJjZGU=");
        registry.add("healthtrace.ocr.encryption-key", () -> "YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=");
        registry.add("healthtrace.ocr.baidu.api-key", () -> "test-api-key");
        registry.add("healthtrace.ocr.baidu.secret-key", () -> "test-secret-key");
    }

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 1, 8, 0);
    private static final LocalDate EXAM_DATE = LocalDate.of(2026, 2, 20);

    @MockitoBean
    private AccountMailSender accountMailSender;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObservationMapper observationMapper;
    @Autowired
    private TempMetricService tempMetricService;
    @Autowired
    private TrendService trendService;
    @Autowired
    private FollowService followService;

    @Test
    void tempMetricLifecycle() {
        long profileId = seedProfile();
        long accountId = accountOf(profileId);
        long r1 = seedReport(profileId);
        long r2 = seedReport(profileId);
        long r3 = seedReport(profileId);
        Observation first = draft(r1, "★促甲状腺激素", "2.0", new BigDecimal("2.0"), "mIU/L", 0);
        observationMapper.insert(first);
        observationMapper.insert(draft(r1, "ALT", "40", new BigDecimal("40"), "U/L", 1));
        observationMapper.insert(draft(r2, "促甲状腺激素", "2.5", new BigDecimal("2.5"), "mIU/l", 0));
        // 不同单位写法：DEC-015 不换算，不归入同一临时指标
        observationMapper.insert(draft(r2, "促甲状腺激素", "2500", new BigDecimal("2500"), "uIU/mL", 1));
        observationMapper.insert(draft(r3, "促甲状腺激素", "3.0", new BigDecimal("3.0"), "mIU/L", 0));
        confirm(r1, EXAM_DATE.minusDays(30));
        confirm(r2, EXAM_DATE);
        // r3 尚未确认：草稿行不归入、不计数

        TempMetricView created = tempMetricService.create(accountId, profileId, first.getId(), true);
        assertThat(created.code()).startsWith("TMP:");
        assertThat(created.name()).isEqualTo("促甲状腺激素");
        assertThat(created.unit()).isEqualTo("mIU/L");
        assertThat(created.observationCount()).isEqualTo(2);
        assertThat(created.followed()).isTrue();
        long tempId = Long.parseLong(created.code().substring(4));
        assertThat(jdbc.queryForObject("SELECT mapping_status FROM temp_metric WHERE id = ?", String.class, tempId))
                .isEqualTo("MERGED");

        // 幂等：再次以同一观测创建返回同一临时指标，关注不重复
        assertThat(tempMetricService.create(accountId, profileId, first.getId(), true)).isEqualTo(created);
        assertThat(tempMetricService.list(accountId, profileId)).containsExactly(created);

        List<FollowedMetricView> follows = followService.list(accountId, profileId);
        assertThat(follows).extracting(FollowedMetricView::metricCode).contains(created.code());
        assertThat(follows).filteredOn(f -> f.metricCode().equals(created.code())).singleElement()
                .satisfies(f -> {
                    assertThat(f.name()).isEqualTo("促甲状腺激素");
                    assertThat(f.temporary()).isTrue();
                });
        // 整体替换关注列表时 TMP 编码同样被接受
        List<String> codes = follows.stream().map(FollowedMetricView::metricCode).toList();
        assertThat(followService.replace(accountId, profileId, codes.reversed()))
                .extracting(FollowedMetricView::metricCode).containsExactlyElementsOf(codes.reversed());

        List<MetricSummary> metrics = trendService.listMetrics(accountId, profileId);
        assertThat(metrics).extracting(MetricSummary::code)
                .contains("ALT", created.code(), "RAW:促甲状腺激素")
                .doesNotHaveDuplicates();
        assertThat(metrics).filteredOn(m -> m.code().equals(created.code())).singleElement()
                .satisfies(m -> {
                    assertThat(m.count()).isEqualTo(2);
                    assertThat(m.standard()).isFalse();
                });
        // 剩余未归入的只有 uIU/mL 那一行
        assertThat(metrics).filteredOn(m -> m.code().equals("RAW:促甲状腺激素")).singleElement()
                .satisfies(m -> assertThat(m.count()).isEqualTo(1));

        TrendView trend = trendService.getTrend(accountId, profileId, created.code(), "custom",
                EXAM_DATE.minusDays(60), EXAM_DATE);
        assertThat(trend.metric().code()).isEqualTo(created.code());
        assertThat(trend.groups()).singleElement().satisfies(g -> {
            assertThat(g.points()).hasSize(2);
            assertThat(g.points().get(1).change().absolute()).isEqualByComparingTo("0.5");
        });

        MetricLatestSummaries summaries = trendService.getLatestSummaries(accountId, profileId, created.code());
        assertThat(summaries.items()).singleElement().satisfies(s -> {
            assertThat(s.metricCode()).isEqualTo(created.code());
            assertThat(s.latest().numericValue()).isEqualByComparingTo("2.5");
        });

        // 新报告确认后同键行自动归入
        confirm(r3, EXAM_DATE.plusDays(10));
        assertThat(tempMetricService.assignReport(profileId, r3)).isEqualTo(1);
        assertThat(observationMapper.findByReport(r3, ObservationStatus.FORMAL)).singleElement()
                .satisfies(o -> assertThat(o.getTempMetricId()).isEqualTo(tempId));
        assertThat(tempMetricService.list(accountId, profileId)).singleElement()
                .satisfies(t -> assertThat(t.observationCount()).isEqualTo(3));

        // 删除：关注移除、观测解除归入、回落为 RAW 汇总
        tempMetricService.delete(accountId, profileId, tempId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM temp_metric WHERE profile_id = ?",
                Integer.class, profileId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM followed_metric WHERE temp_metric_id = ?",
                Integer.class, tempId)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM observation o JOIN report r ON r.id = o.report_id
                WHERE r.profile_id = ? AND o.temp_metric_id IS NOT NULL""", Integer.class, profileId)).isZero();
        assertThat(trendService.listMetrics(accountId, profileId)).extracting(MetricSummary::code)
                .containsExactly("ALT", "RAW:促甲状腺激素");
    }

    // ---- 种子数据（同 MysqlIntegrationTest） ----

    private long accountOf(long profileId) {
        return jdbc.queryForObject(
                "SELECT account_id FROM profile_access WHERE profile_id = ? LIMIT 1", Long.class, profileId);
    }

    private long seedProfile() {
        String email = UUID.randomUUID().toString();
        long accountId = insert("""
                INSERT INTO account (email_ciphertext, email_index, password_hash, status, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?)""",
                email.getBytes(), HexFormat.of().formatHex((email + email).getBytes()).substring(0, 64),
                "{noop}x", NOW, NOW);
        long profileId = insert("""
                INSERT INTO patient_profile (nickname, status, created_at, updated_at)
                VALUES ('测试', 'ACTIVE', ?, ?)""", NOW, NOW);
        insert("""
                INSERT INTO profile_access (account_id, profile_id, relationship, access_status, created_at, updated_at)
                VALUES (?, ?, 'SELF', 'ACTIVE', ?, ?)""", accountId, profileId, NOW, NOW);
        return profileId;
    }

    private long seedReport(long profileId) {
        return insert("""
                INSERT INTO report (profile_id, created_by, processing_status, visibility_status, created_at, updated_at)
                VALUES (?, ?, 'PENDING_CONFIRMATION', 'NORMAL', ?, ?)""", profileId, accountOf(profileId), NOW, NOW);
    }

    private void confirm(long reportId, LocalDate examinationDate) {
        observationMapper.confirmDrafts(reportId, examinationDate, NOW);
        jdbc.update("UPDATE report SET processing_status = 'CONFIRMED', examination_date = ? WHERE id = ?",
                examinationDate, reportId);
        observationMapper.assignMetricDefinitions(reportId);
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
