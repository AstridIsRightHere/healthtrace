package com.healthtrace.integration;

import com.healthtrace.account.service.AccountMailSender;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.indicator.domain.TrendObservation;
import com.healthtrace.indicator.mapper.TrendMapper;
import com.healthtrace.report.service.ReportDeletionService;
import com.healthtrace.report.service.ReportRevisionService;
import com.healthtrace.report.service.RevisionViews.ChangeType;
import com.healthtrace.report.service.RevisionViews.CreateRevisionCommand;
import com.healthtrace.report.service.RevisionViews.ObservationVersionView;
import com.healthtrace.report.service.RevisionViews.OperationType;
import com.healthtrace.report.service.RevisionViews.ReviseResult;
import com.healthtrace.report.service.RevisionViews.RevisionOperation;
import com.healthtrace.report.service.RevisionViews.RevisionView;
import com.healthtrace.report.service.ReviewViews.ObservationInput;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-014 结果级修改的真实 MySQL 验证：修改保留旧行（SUPERSEDED）并新增正式行，删除只软删（RETRACTED），
 * 趋势只读正式行，原始图片与 OCR 任务不被改写；乐观锁冲突；修改过的报告可被彻底删除且无外键失败。
 * 需要 Docker；CI 随 mvn verify 执行。
 */
@SpringBootTest(properties = {"SPRING_PROFILES_ACTIVE=test", "healthtrace.deletion.purge-retry-enabled=false"})
@Testcontainers
class ObservationRevisionIntegrationTest {

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

    @MockitoBean
    private AccountMailSender accountMailSender;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ReportRevisionService revisionService;
    @Autowired
    private ReportDeletionService deletionService;
    @Autowired
    private TrendMapper trendMapper;

    @Test
    void revisionKeepsHistoryAndLeavesOriginalsUntouched() {
        Seed s = seed();
        Map<String, Object> photoBefore = jdbc.queryForMap("SELECT * FROM photo WHERE id = ?", s.photo);
        Map<String, Object> ocrBefore = jdbc.queryForMap("SELECT * FROM ocr_job WHERE id = ?", s.ocrJob);

        ReviseResult result = revisionService.create(s.account, s.report, new CreateRevisionCommand(0L, "核对纸质报告",
                LocalDate.of(2026, 2, 2), List.of(
                new RevisionOperation(OperationType.UPDATE, s.wbc,
                        new ObservationInput(null, "白细胞", "6.1", "10^9/L", "3.5-9.5", null)),
                new RevisionOperation(OperationType.DELETE, s.rbc, null),
                new RevisionOperation(OperationType.ADD, null,
                        new ObservationInput(null, "血小板", "210", "10^9/L", null, s.photo)))));

        assertThat(result.version()).isEqualTo(1);
        assertThat(result.examinationDate()).isEqualTo(LocalDate.of(2026, 2, 2));
        assertThat(result.changeCount()).isEqualTo(4);
        long revisionId = result.revisionId();

        // 旧行：SUPERSEDED，原值不变，记录失效修改
        Map<String, Object> oldWbc = row(s.wbc);
        assertThat(oldWbc.get("status")).isEqualTo("SUPERSEDED");
        assertThat(oldWbc.get("original_value")).isEqualTo("5.6");
        assertThat(((Number) oldWbc.get("retired_revision_id")).longValue()).isEqualTo(revisionId);
        assertThat(oldWbc.get("retired_at")).isNotNull();

        // 新行：FORMAL，指向旧行，来源照片沿用旧行，置信度为空
        Map<String, Object> newWbc = jdbc.queryForMap("SELECT * FROM observation WHERE supersedes_id = ?", s.wbc);
        long newWbcId = ((Number) newWbc.get("id")).longValue();
        assertThat(newWbc.get("status")).isEqualTo("FORMAL");
        assertThat(newWbc.get("original_value")).isEqualTo("6.1");
        assertThat(((Number) newWbc.get("created_revision_id")).longValue()).isEqualTo(revisionId);
        assertThat(((Number) newWbc.get("source_photo_id")).longValue()).isEqualTo(s.photo);
        assertThat(newWbc.get("confidence")).isNull();
        assertThat(newWbc.get("row_order")).isEqualTo(oldWbc.get("row_order"));

        // 删除：软删，行仍在
        Map<String, Object> rbc = row(s.rbc);
        assertThat(rbc.get("status")).isEqualTo("RETRACTED");
        assertThat(rbc.get("original_value")).isEqualTo("4.8");
        assertThat(((Number) rbc.get("retired_revision_id")).longValue()).isEqualTo(revisionId);

        // 新增
        Map<String, Object> plt = jdbc.queryForMap(
                "SELECT * FROM observation WHERE report_id = ? AND original_name = '血小板'", s.report);
        assertThat(plt.get("status")).isEqualTo("FORMAL");
        assertThat(plt.get("supersedes_id")).isNull();
        assertThat(((Number) plt.get("created_revision_id")).longValue()).isEqualTo(revisionId);
        assertThat(((Number) plt.get("row_order")).intValue()).isEqualTo(3);

        assertThat(count("SELECT COUNT(*) FROM observation WHERE report_id = ?", s.report)).isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM observation WHERE report_id = ? AND status = 'FORMAL'", s.report))
                .isEqualTo(2);

        // 趋势/对比只读正式行
        List<TrendObservation> trend = trendMapper.findFormalByReport(s.report);
        assertThat(trend).extracting(TrendObservation::getOriginalValue).containsExactlyInAnyOrder("6.1", "210");

        // 原始图片与 OCR 任务不变
        assertThat(jdbc.queryForMap("SELECT * FROM photo WHERE id = ?", s.photo)).isEqualTo(photoBefore);
        assertThat(jdbc.queryForMap("SELECT * FROM ocr_job WHERE id = ?", s.ocrJob)).isEqualTo(ocrBefore);

        // 修改记录可关联前后行
        List<RevisionView> revisions = revisionService.listRevisions(s.account, s.report);
        assertThat(revisions).hasSize(1);
        RevisionView view = revisions.getFirst();
        assertThat(view.id()).isEqualTo(revisionId);
        assertThat(view.reason()).isEqualTo("核对纸质报告");
        assertThat(view.examinationDate()).isNotNull();
        assertThat(view.changes()).hasSize(3);
        assertThat(view.changes()).anySatisfy(c -> {
            assertThat(c.type()).isEqualTo(ChangeType.UPDATED);
            assertThat(c.observationId()).isEqualTo(s.wbc);
            assertThat(c.newObservationId()).isEqualTo(newWbcId);
            assertThat(c.before().originalValue()).isEqualTo("5.6");
            assertThat(c.after().originalValue()).isEqualTo("6.1");
        });

        // 版本链：最新在前
        List<ObservationVersionView> chain = revisionService.observationHistory(s.account, s.report, s.wbc);
        assertThat(chain).extracting(ObservationVersionView::id).containsExactly(newWbcId, s.wbc);

        // 再次修改新行，旧版本号冲突
        assertThatThrownBy(() -> revisionService.create(s.account, s.report, new CreateRevisionCommand(0L, null,
                null, List.of(new RevisionOperation(OperationType.DELETE, newWbcId, null)))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT));
        assertThat(row(newWbcId).get("status")).isEqualTo("FORMAL");

        // 已失效的行不能再被修改
        assertThatThrownBy(() -> revisionService.create(s.account, s.report, new CreateRevisionCommand(1L, null,
                null, List.of(new RevisionOperation(OperationType.DELETE, s.wbc, null)))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        // 第二次修改：链长 3
        ReviseResult second = revisionService.create(s.account, s.report, new CreateRevisionCommand(1L, null, null,
                List.of(new RevisionOperation(OperationType.UPDATE, newWbcId,
                        new ObservationInput(null, "白细胞", "6.3", "10^9/L", "3.5-9.5", null)))));
        assertThat(second.version()).isEqualTo(2);
        assertThat(revisionService.observationHistory(s.account, s.report, s.wbc)).hasSize(3);
        assertThat(revisionService.listRevisions(s.account, s.report)).hasSize(2);
    }

    @Test
    void revisedReportCanBePurgedWithoutForeignKeyFailures() {
        Seed s = seed();
        revisionService.create(s.account, s.report, new CreateRevisionCommand(0L, null, null, List.of(
                new RevisionOperation(OperationType.UPDATE, s.wbc,
                        new ObservationInput(null, "白细胞", "6.1", null, null, null)),
                new RevisionOperation(OperationType.DELETE, s.rbc, null))));
        long newWbc = jdbc.queryForObject("SELECT id FROM observation WHERE supersedes_id = ?", Long.class, s.wbc);
        revisionService.create(s.account, s.report, new CreateRevisionCommand(1L, null, null, List.of(
                new RevisionOperation(OperationType.UPDATE, newWbc,
                        new ObservationInput(null, "白细胞", "6.2", null, null, null)))));

        deletionService.delete(s.account, s.report);

        for (String table : new String[]{"report", "photo", "ocr_job", "observation", "report_revision"}) {
            String column = table.equals("report") ? "id" : "report_id";
            assertThat(count("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", s.report))
                    .as(table).isZero();
        }
    }

    // ---- 种子数据 ----

    private record Seed(long account, long report, long photo, long ocrJob, long wbc, long rbc) {
    }

    private Seed seed() {
        long account = seedAccount();
        long profile = seedProfile(account);
        long report = insert("""
                INSERT INTO report (profile_id, created_by, examination_date, processing_status, visibility_status,
                                    created_at, updated_at)
                VALUES (?, ?, ?, 'CONFIRMED', 'NORMAL', ?, ?)""", profile, account, EXAM, NOW, NOW);
        long photo = insert("""
                INSERT INTO photo (report_id, profile_id, object_key, content_digest, media_type, byte_size,
                                   preview_object_key, preview_media_type, preview_byte_size, page_order,
                                   storage_status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'image/jpeg', 4, ?, 'image/jpeg', 4, 1, 'COMPLETE', ?, ?)""",
                report, profile, "reports/" + report + "/o", "a".repeat(64), "reports/" + report + "/p", NOW, NOW);
        long ocrJob = insert("""
                INSERT INTO ocr_job (report_id, provider, consent_declared_at, created_by, status, created_at, updated_at)
                VALUES (?, 'baidu-medical-report', ?, ?, 'SUCCEEDED', ?, ?)""", report, NOW, account, NOW, NOW);
        long wbc = observation(report, "白细胞", "5.6", 1, photo);
        long rbc = observation(report, "红细胞", "4.8", 2, photo);
        return new Seed(account, report, photo, ocrJob, wbc, rbc);
    }

    private long observation(long report, String name, String value, int rowOrder, long photo) {
        return insert("""
                INSERT INTO observation (report_id, original_name, original_value, result_kind, status, row_order,
                                         source_photo_id, examination_date, confidence, created_at, updated_at)
                VALUES (?, ?, ?, 'EXACT', 'FORMAL', ?, ?, ?, 0.9, ?, ?)""",
                report, name, value, rowOrder, photo, EXAM, NOW, NOW);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM observation WHERE id = ?", id);
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
                VALUES ('本人', 'ACTIVE', ?, ?)""", NOW, NOW);
        jdbc.update("""
                INSERT INTO profile_access (account_id, profile_id, relationship, access_status, created_at, updated_at)
                VALUES (?, ?, 'SELF', 'ACTIVE', ?, ?)""", accountId, profileId, NOW, NOW);
        return profileId;
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
