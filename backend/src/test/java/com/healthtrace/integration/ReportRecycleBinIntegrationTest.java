package com.healthtrace.integration;

import com.healthtrace.account.service.AccountMailSender;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.indicator.domain.TrendObservation;
import com.healthtrace.indicator.mapper.TrendMapper;
import com.healthtrace.report.domain.ReportListFilter;
import com.healthtrace.report.service.ReportDeletionService;
import com.healthtrace.report.service.ReportListRemovalService;
import com.healthtrace.report.service.ReportQueryService;
import com.healthtrace.report.service.ReportViews.ReportListItem;
import com.healthtrace.rights.deletion.DeletionEventType;
import com.healthtrace.rights.deletion.DeletionLedgerService;
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

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-016 回收站的真实 MySQL 验证：列表移除后退出默认/待处理/已确认列表与趋势图，出现在 status=REMOVED；
 * 可恢复；回收站中的报告可彻底删除（无孤儿行 + 账本），他人或已删除 404。需要 Docker。
 */
@SpringBootTest(properties = {"SPRING_PROFILES_ACTIVE=test", "healthtrace.deletion.purge-retry-enabled=false"})
@Testcontainers
class ReportRecycleBinIntegrationTest {

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
    private static final LocalDate EXAM = LocalDate.of(2026, 2, 1);

    // 与数据库验证无关，mock 掉以免依赖邮件配置
    @MockitoBean
    private AccountMailSender accountMailSender;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ReportListRemovalService listRemovalService;
    @Autowired
    private ReportQueryService queryService;
    @Autowired
    private ReportDeletionService deletionService;
    @Autowired
    private DeletionLedgerService ledgerService;
    @Autowired
    private TrendMapper trendMapper;

    @Test
    void removedReportLeavesListsAndTrendsThenRestores() {
        long account = seedAccount();
        long profile = seedProfile(account);
        long confirmed = seedReport(profile, account, "CONFIRMED", EXAM);
        long draft = seedReport(profile, account, "PENDING_CONFIRMATION", null);
        long kept = seedReport(profile, account, "CONFIRMED", EXAM.minusDays(10));
        seedObservation(confirmed);
        seedObservation(kept);

        listRemovalService.remove(account, confirmed);
        listRemovalService.remove(account, draft);
        // 幂等：再次移除仍成功，只记一次审计
        listRemovalService.remove(account, confirmed);

        assertThat(ids(ReportListFilter.CONFIRMED, account, profile)).containsExactly(kept);
        assertThat(ids(ReportListFilter.DRAFT, account, profile)).isEmpty();
        var removed = queryService.listReports(account, profile, ReportListFilter.REMOVED, null);
        assertThat(removed.total()).isEqualTo(2);
        assertThat(removed.items()).extracting(ReportListItem::id).containsExactlyInAnyOrder(confirmed, draft);
        assertThat(removed.items()).allSatisfy(i -> assertThat(i.removedAt()).isNotNull());
        assertThat(queryService.listReports(account, profile, ReportListFilter.REMOVED, 1).items()).hasSize(1);
        assertThat(trendMapper.findFormalByProfile(profile))
                .extracting(TrendObservation::getReportId).containsOnly(kept);
        assertThat(jdbc.queryForObject("SELECT version FROM report WHERE id = ?", Long.class, confirmed))
                .isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM audit_event WHERE action = 'REPORT_LIST_REMOVE' AND actor_account_id = ?",
                account)).isEqualTo(2);

        listRemovalService.restore(account, confirmed);
        assertThat(ids(ReportListFilter.CONFIRMED, account, profile)).containsExactly(confirmed, kept);
        assertThat(ids(ReportListFilter.REMOVED, account, profile)).containsExactly(draft);
        assertThat(trendMapper.findFormalByProfile(profile))
                .extracting(TrendObservation::getReportId).containsExactlyInAnyOrder(confirmed, kept);
        // 已恢复的报告再次恢复：409
        assertThatThrownBy(() -> listRemovalService.restore(account, confirmed))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.REPORT_STATE_CONFLICT);
    }

    @Test
    void removedReportCanBePurgedOnlyByOwnerAndOnce() {
        long account = seedAccount();
        long profile = seedProfile(account);
        long report = seedReport(profile, account, "CONFIRMED", EXAM);
        seedObservation(report);
        insert("""
                INSERT INTO ocr_job (report_id, provider, consent_declared_at, created_by, status, created_at, updated_at)
                VALUES (?, 'baidu-medical-report', ?, ?, 'SUCCEEDED', ?, ?)""", report, NOW, account, NOW, NOW);
        listRemovalService.remove(account, report);

        long stranger = seedAccount();
        assertThatThrownBy(() -> deletionService.delete(stranger, report))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThatThrownBy(() -> listRemovalService.restore(stranger, report))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(count("SELECT COUNT(*) FROM report WHERE id = ?", report)).isEqualTo(1);

        deletionService.delete(account, report);

        for (String table : new String[]{"report", "photo", "ocr_job", "observation", "report_revision",
                "visit_report"}) {
            String column = table.equals("report") ? "id" : "report_id";
            assertThat(count("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", report))
                    .as(table).isZero();
        }
        String digest = ledgerService.digest(DeletionEventType.REPORT_PURGE, report);
        assertThat(count("SELECT COUNT(*) FROM deletion_ledger WHERE subject_digest = ?", digest)).isEqualTo(1);
        assertThat(ids(ReportListFilter.REMOVED, account, profile)).isEmpty();

        // 已彻底删除：删除、恢复、移除均 404，不再写账本
        for (Runnable op : new Runnable[]{() -> deletionService.delete(account, report),
                () -> listRemovalService.restore(account, report),
                () -> listRemovalService.remove(account, report)}) {
            assertThatThrownBy(op::run)
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        }
        assertThat(count("SELECT COUNT(*) FROM deletion_ledger WHERE subject_digest = ?", digest)).isEqualTo(1);
    }

    // ---- 辅助 ----

    private java.util.List<Long> ids(ReportListFilter filter, long account, long profile) {
        return queryService.listReports(account, profile, filter, null).items().stream()
                .map(ReportListItem::id).toList();
    }

    private long count(String sql, Object arg) {
        return jdbc.queryForObject(sql, Long.class, arg);
    }

    private long seedAccount() {
        String email = UUID.randomUUID().toString();
        return insert("""
                INSERT INTO account (email_ciphertext, email_index, password_hash, status, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?)""",
                email.getBytes(), HexFormat.of().formatHex((email + email).getBytes()).substring(0, 64),
                "{noop}x", NOW, NOW);
    }

    private long seedProfile(long accountId) {
        long profileId = insert("""
                INSERT INTO patient_profile (nickname, status, created_at, updated_at)
                VALUES ('测试', 'ACTIVE', ?, ?)""", NOW, NOW);
        insert("""
                INSERT INTO profile_access (account_id, profile_id, relationship, access_status, created_at, updated_at)
                VALUES (?, ?, 'SELF', 'ACTIVE', ?, ?)""", accountId, profileId, NOW, NOW);
        return profileId;
    }

    private long seedReport(long profileId, long accountId, String processing, LocalDate examDate) {
        return insert("""
                INSERT INTO report (profile_id, created_by, examination_date, processing_status, visibility_status,
                                    created_at, updated_at)
                VALUES (?, ?, ?, ?, 'NORMAL', ?, ?)""",
                profileId, accountId, examDate, processing, NOW, NOW);
    }

    private void seedObservation(long reportId) {
        insert("""
                INSERT INTO observation (report_id, original_name, original_value, result_kind, status, row_order,
                                         examination_date, created_at, updated_at)
                VALUES (?, '白细胞', '5.6', 'EXACT', 'FORMAL', 1, ?, ?, ?)""", reportId, EXAM, NOW, NOW);
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
