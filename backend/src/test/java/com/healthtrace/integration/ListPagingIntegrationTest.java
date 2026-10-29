package com.healthtrace.integration;

import com.healthtrace.account.service.AccountMailSender;
import com.healthtrace.report.domain.ReportSummary;
import com.healthtrace.report.mapper.ReportMapper;
import com.healthtrace.visit.domain.Visit;
import com.healthtrace.visit.mapper.VisitMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 列表分页 SQL 的真实 MySQL 验证：LIMIT 只截取结果、COUNT 与列表筛选条件一致（可见性、处理状态、档案隔离）、
 * limit 为空时返回全部且排序不变。需要 Docker；CI（ubuntu-latest）随 mvn verify 执行。
 */
// spring.profiles.active 在环境准备阶段即解析，@DynamicPropertySource 太晚，须用 properties 提供占位符
// 顺带验证 OpenAPI 文档（非 dev 默认关闭，这里显式打开），复用同一容器以免多起一个 MySQL
@SpringBootTest(properties = {"SPRING_PROFILES_ACTIVE=test",
        "springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
@AutoConfigureMockMvc
@Testcontainers
class ListPagingIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void openApiDocsAreAnonymousAndListEndpoints() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/api/v1/patient-profiles/{profileId}/followed-metrics")))
                .andExpect(content().string(containsString("/api/v1/patient-profiles/{profileId}/reports")));
    }

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

    // 与数据库验证无关，mock 掉以免依赖邮件配置
    @MockitoBean
    private AccountMailSender accountMailSender;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ReportMapper reportMapper;
    @Autowired
    private VisitMapper visitMapper;

    @Test
    void pendingReportsLimitAndCountShareFilter() {
        long profile = seedProfile();
        long r1 = seedReport(profile, "PENDING_CONFIRMATION", "NORMAL", null, NOW.minusHours(3));
        long r2 = seedReport(profile, "FAILED", "NORMAL", null, NOW.minusHours(2));
        long r3 = seedReport(profile, "UPLOADING", "NORMAL", null, NOW.minusHours(1));
        // 以下不应出现在待处理列表或计数中
        seedReport(profile, "CONFIRMED", "NORMAL", LocalDate.of(2026, 1, 1), NOW);
        seedReport(profile, "PENDING_CONFIRMATION", "LIST_REMOVED", null, NOW);
        seedReport(seedProfile(), "PENDING_CONFIRMATION", "NORMAL", null, NOW);

        assertThat(reportMapper.findPendingByProfile(profile, 2))
                .extracting(ReportSummary::getId).containsExactly(r3, r2);
        assertThat(reportMapper.findPendingByProfile(profile, null))
                .extracting(ReportSummary::getId).containsExactly(r3, r2, r1);
        assertThat(reportMapper.countPendingByProfile(profile)).isEqualTo(3);
    }

    @Test
    void confirmedReportsLimitAndCountShareFilter() {
        long profile = seedProfile();
        long older = seedReport(profile, "CONFIRMED", "NORMAL", LocalDate.of(2025, 6, 1), NOW);
        long newer = seedReport(profile, "CONFIRMED", "NORMAL", LocalDate.of(2026, 2, 1), NOW);
        long sameDay = seedReport(profile, "CONFIRMED", "NORMAL", LocalDate.of(2026, 2, 1), NOW);
        seedReport(profile, "PENDING_CONFIRMATION", "NORMAL", null, NOW);
        seedReport(profile, "CONFIRMED", "DELETING", LocalDate.of(2026, 2, 2), NOW);

        assertThat(reportMapper.findConfirmedByProfile(profile, 1))
                .extracting(ReportSummary::getId).containsExactly(sameDay);
        assertThat(reportMapper.findConfirmedByProfile(profile, null))
                .extracting(ReportSummary::getId).containsExactly(sameDay, newer, older);
        assertThat(reportMapper.countConfirmedByProfile(profile)).isEqualTo(3);
    }

    @Test
    void visitsLimitAndCountArePerProfile() {
        long profile = seedProfile();
        long v1 = seedVisit(profile, LocalDate.of(2025, 12, 1));
        long v2 = seedVisit(profile, LocalDate.of(2026, 1, 1));
        long v3 = seedVisit(profile, LocalDate.of(2026, 1, 1));
        seedVisit(seedProfile(), LocalDate.of(2026, 2, 1));

        assertThat(visitMapper.findByProfile(profile, 2)).extracting(Visit::getId).containsExactly(v3, v2);
        assertThat(visitMapper.findByProfile(profile, null)).extracting(Visit::getId).containsExactly(v3, v2, v1);
        assertThat(visitMapper.countByProfile(profile)).isEqualTo(3);
        assertThat(visitMapper.countByProfile(Long.MAX_VALUE)).isZero();
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

    private long ownerOf(long profileId) {
        return jdbc.queryForObject(
                "SELECT account_id FROM profile_access WHERE profile_id = ? LIMIT 1", Long.class, profileId);
    }

    private long seedReport(long profileId, String processing, String visibility, LocalDate examDate,
                            LocalDateTime createdAt) {
        return insert("""
                INSERT INTO report (profile_id, created_by, examination_date, processing_status, visibility_status,
                                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                profileId, ownerOf(profileId), examDate, processing, visibility, createdAt, createdAt);
    }

    private long seedVisit(long profileId, LocalDate visitDate) {
        return insert("""
                INSERT INTO visit (profile_id, created_by, visit_date, hospital_text, created_at, updated_at)
                VALUES (?, ?, ?, '测试医院', ?, ?)""", profileId, ownerOf(profileId), visitDate, NOW, NOW);
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
