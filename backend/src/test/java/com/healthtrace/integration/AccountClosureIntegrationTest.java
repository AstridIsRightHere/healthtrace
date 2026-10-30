package com.healthtrace.integration;

import com.healthtrace.account.domain.AccountStatus;
import com.healthtrace.account.service.AccountClosureService;
import com.healthtrace.account.service.AccountMailSender;
import com.healthtrace.account.service.AccountService;
import com.healthtrace.account.service.AccountView;
import com.healthtrace.common.security.OpaqueTokens;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.profile.domain.Relationship;
import com.healthtrace.profile.service.ProfileService;
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
 * 账号注销（FR-017）的真实 MySQL 验证：外键顺序、单事务删除、处置台账与审计、同一邮箱可重新开通。
 * 需要 Docker；CI（ubuntu-latest）随 mvn verify 执行。
 */
// spring.profiles.active 在环境准备阶段即解析，@DynamicPropertySource 太晚，须用 properties 提供占位符
@SpringBootTest(properties = "SPRING_PROFILES_ACTIVE=test")
@Testcontainers
class AccountClosureIntegrationTest {

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
        // 关闭存储清理定时重试，避免后台任务干扰本测试的断言
        registry.add("healthtrace.deletion.purge-retry-enabled", () -> "false");
    }

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 1, 8, 0);
    private static final String PASSWORD = "correct horse battery";

    // 集成测试不发真实邮件
    @MockitoBean
    private AccountMailSender accountMailSender;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AccountService accountService;
    @Autowired
    private ProfileService profileService;
    @Autowired
    private AccountClosureService closureService;

    @Test
    void closureRemovesEveryRowAndEmailCanRegisterAgain() {
        long issuer = seedIssuer();
        String email = UUID.randomUUID() + "@example.com";
        AccountView account = accountService.register(seedInvitation(issuer), email, PASSWORD);
        long accountId = account.id();
        long profileId = profileService.createProfile(accountId, Relationship.SELF, "测试", true).id();

        // 一份已确认报告：结果、修改记录、OCR 任务（引用同意记录）、就诊关联
        long consentId = insert("""
                INSERT INTO consent_record (account_id, profile_id, purpose, subject_role, notice_version, decision,
                                            provider_scope, recorded_at)
                VALUES (?, ?, 'OCR_PROCESSING', 'SELF', 'v1', 'GRANTED', 'BAIDU_OCR', ?)""",
                accountId, profileId, NOW);
        long reportId = insert("""
                INSERT INTO report (profile_id, created_by, processing_status, visibility_status, confirmed_by,
                                    confirmed_at, examination_date, created_at, updated_at)
                VALUES (?, ?, 'CONFIRMED', 'NORMAL', ?, ?, ?, ?, ?)""",
                profileId, accountId, accountId, NOW, LocalDate.of(2026, 2, 20), NOW, NOW);
        insert("""
                INSERT INTO observation (report_id, original_name, original_value, result_kind, status, row_order,
                                         created_at, updated_at)
                VALUES (?, 'WBC', '5.1', 'EXACT', 'FORMAL', 0, ?, ?)""", reportId, NOW, NOW);
        insert("""
                INSERT INTO ocr_job (report_id, provider, consent_record_id, consent_declared_at, created_by, status,
                                     created_at, updated_at)
                VALUES (?, 'BAIDU', ?, ?, ?, 'SUCCEEDED', ?, ?)""", reportId, consentId, NOW, accountId, NOW, NOW);
        insert("""
                INSERT INTO report_revision (report_id, actor_account_id, from_version, to_version, change_payload,
                                             created_at)
                VALUES (?, ?, 0, 1, ?, ?)""", reportId, accountId, new byte[]{1}, NOW);
        long visitId = insert("""
                INSERT INTO visit (profile_id, created_by, visit_date, hospital_text, created_at, updated_at)
                VALUES (?, ?, ?, '某医院', ?, ?)""", profileId, accountId, LocalDate.of(2026, 2, 21), NOW, NOW);
        jdbc.update("INSERT INTO visit_report (visit_id, report_id, created_at) VALUES (?, ?, ?)",
                visitId, reportId, NOW);
        // 本人签发、尚未使用的邀请
        seedInvitation(accountId);
        assertThat(count("SELECT COUNT(*) FROM followed_metric WHERE profile_id = ?", profileId)).isEqualTo(6);

        int removed = closureService.close(accountId, PASSWORD);

        assertThat(removed).isPositive();
        assertThat(count("SELECT COUNT(*) FROM account WHERE id = ?", accountId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM patient_profile WHERE id = ?", profileId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM profile_access WHERE account_id = ?", accountId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM report WHERE profile_id = ?", profileId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM observation WHERE report_id = ?", reportId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM ocr_job WHERE report_id = ?", reportId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM report_revision WHERE report_id = ?", reportId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM visit WHERE profile_id = ?", profileId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM visit_report WHERE visit_id = ?", visitId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM followed_metric WHERE profile_id = ?", profileId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE account_id = ?", accountId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM auth_token WHERE account_id = ?", accountId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM invitation WHERE issuer_account_id = ?", accountId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM invitation WHERE used_by = ?", accountId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM deletion_ledger WHERE event_type = 'ACCOUNT_CLOSURE'"))
                .isGreaterThanOrEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM deletion_ledger WHERE event_type = 'REPORT_PURGE'"))
                .isGreaterThanOrEqualTo(1);
        assertThat(count("""
                SELECT COUNT(*) FROM audit_event
                WHERE action = 'ACCOUNT_CLOSE' AND outcome = 'SUCCESS' AND actor_account_id = ?""", accountId))
                .isEqualTo(1);

        // 同一邮箱凭新邀请码可重新开通
        AccountView again = accountService.register(seedInvitation(issuer), email, PASSWORD);
        assertThat(again.id()).isNotEqualTo(accountId);
        assertThat(again.status()).isEqualTo(AccountStatus.UNVERIFIED);
    }

    @Test
    void wrongPasswordRollsBackAndKeepsData() {
        long issuer = seedIssuer();
        AccountView account = accountService.register(seedInvitation(issuer),
                UUID.randomUUID() + "@example.com", PASSWORD);
        long profileId = profileService.createProfile(account.id(), Relationship.SELF, "测试", true).id();

        assertThatThrownBy(() -> closureService.close(account.id(), "wrong password!!"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BAD_CREDENTIALS));

        assertThat(count("SELECT COUNT(*) FROM account WHERE id = ?", account.id())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM patient_profile WHERE id = ?", profileId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM followed_metric WHERE profile_id = ?", profileId)).isEqualTo(6);
    }

    // ---- 种子数据 ----

    private long seedIssuer() {
        String email = UUID.randomUUID().toString();
        return insert("""
                INSERT INTO account (email_ciphertext, email_index, password_hash, status, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?)""",
                email.getBytes(), HexFormat.of().formatHex((email + email).getBytes()).substring(0, 64),
                "{noop}x", NOW, NOW);
    }

    /** 插入一条可用邀请，返回邀请码原文。 */
    private String seedInvitation(long issuerAccountId) {
        String code = OpaqueTokens.generate();
        insert("""
                INSERT INTO invitation (issuer_account_id, token_digest, created_at, updated_at)
                VALUES (?, ?, ?, ?)""", issuerAccountId, OpaqueTokens.digest(code), NOW, NOW);
        return code;
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
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
