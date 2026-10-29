package com.healthtrace.integration;

import com.healthtrace.account.service.AccountMailSender;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.report.service.ReportDeletionService;
import com.healthtrace.rights.deletion.DeletionEventType;
import com.healthtrace.rights.deletion.DeletionLedgerService;
import com.healthtrace.storage.StorageService;
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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-016 报告彻底删除的真实 MySQL 验证：已确认报告连同图片、OCR 任务、结果、修改记录、就诊关联一并删除，
 * 外键顺序正确、无孤儿行；写一条 REPORT_PURGE 账本；存储对象经 outbox 在提交后删除，账本转为 COMPLETED。
 * 需要 Docker；CI（ubuntu-latest）随 mvn verify 执行。
 */
@SpringBootTest(properties = {"SPRING_PROFILES_ACTIVE=test", "healthtrace.deletion.purge-retry-enabled=false"})
@Testcontainers
class ReportPurgeIntegrationTest {

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

    @MockitoBean
    private AccountMailSender accountMailSender;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ReportDeletionService deletionService;
    @Autowired
    private DeletionLedgerService ledgerService;
    @Autowired
    private StorageService storageService;

    @Test
    void confirmedReportIsPurgedWithoutOrphansAndStorageIsCleaned() throws Exception {
        long account = seedAccount();
        long profile = seedProfile(account);
        long report = seedReport(profile, account);
        // 对照组：同档案另一份报告不受影响
        long other = seedReport(profile, account);

        String original = "reports/" + report + "/" + UUID.randomUUID() + "/original";
        String preview = "reports/" + report + "/" + UUID.randomUUID() + "/preview";
        put(original);
        put(preview);
        long photo = insert("""
                INSERT INTO photo (report_id, profile_id, object_key, content_digest, media_type, byte_size,
                                   preview_object_key, preview_media_type, preview_byte_size, page_order,
                                   storage_status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'image/jpeg', 4, ?, 'image/jpeg', 4, 1, 'COMPLETE', ?, ?)""",
                report, profile, original, "a".repeat(64), preview, NOW, NOW);
        insert("""
                INSERT INTO ocr_job (report_id, provider, consent_declared_at, created_by, status, created_at, updated_at)
                VALUES (?, 'baidu-medical-report', ?, ?, 'SUCCEEDED', ?, ?)""", report, NOW, account, NOW, NOW);
        insert("""
                INSERT INTO observation (report_id, original_name, original_value, result_kind, status, row_order,
                                         source_photo_id, created_at, updated_at)
                VALUES (?, '白细胞', '5.6', 'EXACT', 'FORMAL', 1, ?, ?, ?)""", report, photo, NOW, NOW);
        insert("""
                INSERT INTO report_revision (report_id, actor_account_id, from_version, to_version, change_payload,
                                             created_at)
                VALUES (?, ?, 0, 1, ?, ?)""", report, account, new byte[]{1, 2, 3}, NOW);
        long visit = insert("""
                INSERT INTO visit (profile_id, created_by, visit_date, hospital_text, created_at, updated_at)
                VALUES (?, ?, ?, '测试医院', ?, ?)""", profile, account, LocalDate.of(2026, 2, 1), NOW, NOW);
        jdbc.update("INSERT INTO visit_report (visit_id, report_id, created_at) VALUES (?, ?, ?)", visit, report, NOW);
        jdbc.update("INSERT INTO visit_report (visit_id, report_id, created_at) VALUES (?, ?, ?)", visit, other, NOW);

        deletionService.delete(account, report);

        for (String table : new String[]{"report", "photo", "ocr_job", "observation", "report_revision",
                "visit_report"}) {
            String column = table.equals("report") ? "id" : "report_id";
            assertThat(count("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", report))
                    .as(table).isZero();
        }
        // 就诊本身与另一份报告的关联保留
        assertThat(count("SELECT COUNT(*) FROM visit WHERE id = ?", visit)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM visit_report WHERE report_id = ?", other)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM report WHERE id = ?", other)).isEqualTo(1);

        String digest = ledgerService.digest(DeletionEventType.REPORT_PURGE, report);
        Map<String, Object> ledger = jdbc.queryForMap(
                "SELECT id, event_type, item_count FROM deletion_ledger WHERE subject_digest = ?", digest);
        assertThat(ledger.get("event_type")).isEqualTo("REPORT_PURGE");
        // 修改记录 1 + 就诊关联 1 + 结果 1 + OCR 1 + 图片 1 + 报告 1
        assertThat(((Number) ledger.get("item_count")).intValue()).isEqualTo(6);
        long ledgerId = ((Number) ledger.get("id")).longValue();
        // 账本不含明文 id
        assertThat(digest).hasSize(64).doesNotContain("report");

        // 提交后异步清理：等待 outbox 清空
        long deadline = System.currentTimeMillis() + 10_000;
        // 异步线程先删任务行、再更新账本状态：两步之间存在窗口，必须等待最终状态（账本 COMPLETED），
        // 只等任务行清空会在较慢的 CI 机器上读到 STORAGE_PENDING。
        while ((count("SELECT COUNT(*) FROM storage_purge_task WHERE ledger_id = ?", ledgerId) > 0
                || !"COMPLETED".equals(jdbc.queryForObject(
                        "SELECT status FROM deletion_ledger WHERE id = ?", String.class, ledgerId)))
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(count("SELECT COUNT(*) FROM storage_purge_task WHERE ledger_id = ?", ledgerId)).isZero();
        assertThat(storageService.exists(original)).isFalse();
        assertThat(storageService.exists(preview)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM deletion_ledger WHERE id = ?", String.class, ledgerId))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT completed_at FROM deletion_ledger WHERE id = ?",
                LocalDateTime.class, ledgerId)).isNotNull();

        // 再次删除：404，不再写账本
        assertThatThrownBy(() -> deletionService.delete(account, report)).isInstanceOf(ApiException.class);
        assertThat(count("SELECT COUNT(*) FROM deletion_ledger WHERE subject_digest = ?", digest)).isEqualTo(1);
    }

    @Test
    void othersCannotPurgeAndNothingChanges() {
        long owner = seedAccount();
        long profile = seedProfile(owner);
        long report = seedReport(profile, owner);
        long stranger = seedAccount();

        assertThatThrownBy(() -> deletionService.delete(stranger, report)).isInstanceOf(ApiException.class);
        assertThat(count("SELECT COUNT(*) FROM report WHERE id = ?", report)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM deletion_ledger WHERE subject_digest = ?",
                ledgerService.digest(DeletionEventType.REPORT_PURGE, report))).isZero();
    }

    // ---- 种子数据 ----

    private void put(String key) {
        byte[] bytes = "data".getBytes(StandardCharsets.US_ASCII);
        storageService.put(key, new ByteArrayInputStream(bytes), bytes.length, "image/jpeg");
        assertThat(storageService.exists(key)).isTrue();
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

    private long seedReport(long profileId, long accountId) {
        return insert("""
                INSERT INTO report (profile_id, created_by, examination_date, processing_status, visibility_status,
                                    created_at, updated_at)
                VALUES (?, ?, ?, 'CONFIRMED', 'NORMAL', ?, ?)""",
                profileId, accountId, LocalDate.of(2026, 2, 1), NOW, NOW);
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
