package com.healthtrace.report.service;

import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.FieldErrorItem;
import com.healthtrace.profile.mapper.ProfileMapper;
import com.healthtrace.report.domain.DetectedMediaType;
import com.healthtrace.report.domain.DuplicateDecision;
import com.healthtrace.report.domain.Photo;
import com.healthtrace.report.domain.ProcessingStatus;
import com.healthtrace.report.domain.Report;
import com.healthtrace.report.domain.ReportKind;
import com.healthtrace.report.domain.ReportPolicy;
import com.healthtrace.report.domain.ReportType;
import com.healthtrace.report.domain.StorageStatus;
import com.healthtrace.report.domain.VisibilityStatus;
import com.healthtrace.report.mapper.ObservationMapper;
import com.healthtrace.report.mapper.PhotoMapper;
import com.healthtrace.report.mapper.ReportMapper;
import com.healthtrace.report.service.ReportViews.PhotoUploadResult;
import com.healthtrace.report.service.ReportViews.PhotoView;
import com.healthtrace.report.service.ReportViews.ReportView;
import com.healthtrace.rights.audit.AuditAction;
import com.healthtrace.rights.audit.AuditService;
import com.healthtrace.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 报告草稿创建与图片上传（FR-004、FR-005，DEC-008）：类型探测、大小与配额校验、内容摘要去重、写入存储。
 * <p>
 * 访问控制：任何 reportId 都先解析到 profileId，再要求当前账号持有该档案的 ACTIVE 授权；
 * 不存在与无权访问统一返回 404 HT-3001，避免暴露其他档案的资源是否存在。
 * <p>
 * 一致性：图片先以 PENDING 落库占用页序 → 写入存储 → 标记 COMPLETE；写入失败则删除已写对象并标记 FAILED。
 * 页序 1..20 + 唯一约束 uk_photo_report_page 在并发下兜底“单报告最多 20 个文件”。
 */
@Service
public class ReportUploadService {

    private static final Logger log = LoggerFactory.getLogger(ReportUploadService.class);

    /** photo.original_filename 列宽（V12），按码点截断。 */
    static final int ORIGINAL_FILENAME_MAX = 255;

    private final ReportAccess access;
    private final ReportMapper reportMapper;
    private final PhotoMapper photoMapper;
    private final ObservationMapper observationMapper;
    private final ProfileMapper profileMapper;
    private final StorageService storageService;
    private final AuditService auditService;
    private final Clock clock;

    public ReportUploadService(ReportAccess access, ReportMapper reportMapper, PhotoMapper photoMapper,
                               ObservationMapper observationMapper, ProfileMapper profileMapper, StorageService storageService,
                               AuditService auditService, Clock clock) {
        this.access = access;
        this.reportMapper = reportMapper;
        this.photoMapper = photoMapper;
        this.observationMapper = observationMapper;
        this.profileMapper = profileMapper;
        this.storageService = storageService;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * 新建报告草稿（UPLOADING）。标题、检查日期可选，可在确认阶段再补。
     * reportKind 区分结构化检验报告与影像/非结构化报告；影像报告可直接携带结论文本。
     */
    public ReportView createReport(long accountId, long profileId, String title, LocalDate examinationDate,
                                   String reportKindStr, String conclusionText) {
        if (profileMapper.findActiveByAccountAndProfile(accountId, profileId) == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        access.requireVerifiedEmail(accountId);
        String normalizedTitle = normalizeTitle(title);
        requireNotFuture(examinationDate);

        ReportKind reportKind = parseKind(reportKindStr);
        String normalizedConclusion = normalizeConclusion(conclusionText);
        if (normalizedConclusion != null && reportKind == ReportKind.STRUCTURED) {
            // 结论文本是影像/非结构化报告的主要内容；结构化检验报告的结论由指标核对产生
            throw validation("conclusionText", "NotAllowed", "检验报告不支持直接填写结论，请改选影像或其他报告");
        }

        LocalDateTime now = now();
        Report report = new Report();
        report.setProfileId(profileId);
        report.setCreatedBy(accountId);
        report.setTitle(normalizedTitle);
        report.setReportType(ReportType.UNKNOWN);
        report.setReportKind(reportKind);
        report.setConclusionText(normalizedConclusion);
        report.setExaminationDate(examinationDate);
        report.setProcessingStatus(ProcessingStatus.UPLOADING);
        report.setVisibilityStatus(VisibilityStatus.NORMAL);
        report.setVersion(0L);
        report.setCreatedAt(now);
        report.setUpdatedAt(now);
        reportMapper.insert(report);
        auditService.success(accountId, AuditAction.REPORT_CREATE, AuditService.token("report", report.getId()));
        return toView(report);
    }

    /**
     * 影像/非结构化报告归档（任务二）：不走 OCR 与指标核对，上传至少一个文件后直接置为已确认。
     * 他人报告 404；结构化报告或非上传中状态 409 HT-4002；无已完成文件 409 HT-4002。
     */
    public ReportView completeImaging(long accountId, long reportId) {
        Report report = access.requireOwnedReport(accountId, reportId);
        access.requireVerifiedEmail(accountId);
        if (report.getReportKind() == ReportKind.STRUCTURED) {
            throw new ApiException(ErrorCode.REPORT_STATE_CONFLICT, "检验报告需要核对指标后确认");
        }
        if (report.getProcessingStatus() != ProcessingStatus.UPLOADING) {
            throw new ApiException(ErrorCode.REPORT_STATE_CONFLICT);
        }
        if (photoMapper.findCompleteByReport(reportId).isEmpty()) {
            throw new ApiException(ErrorCode.REPORT_STATE_CONFLICT, "报告还没有上传完成的文件");
        }
        LocalDateTime now = now();
        if (reportMapper.completeWithoutObservations(reportId, accountId, now) == 0) {
            throw new ApiException(ErrorCode.REPORT_STATE_CONFLICT);
        }
        auditService.success(accountId, AuditAction.REPORT_CONFIRM, AuditService.token("report", reportId));
        report.setProcessingStatus(ProcessingStatus.CONFIRMED);
        return toView(report);
    }

    /**
     * 编辑报告元数据（标题、检查日期）：两个字段按请求原样写入（空白标题视为 null）。
     * 任何处理状态均可；仅 NORMAL 可见性（回收站中、已删除、他人报告统一 404）。
     * 校验规则与新建报告一致；已确认的检验报告必须保留检查日期（其正式结果依赖该日期参与趋势）。
     * 检查日期变化时同步到该报告当前的正式结果行（与 FR-014 修改一致）；草稿行在确认时统一写入日期。
     */
    @Transactional
    public ReportView updateMetadata(long accountId, long reportId, String title, LocalDate examinationDate) {
        Report report = access.requireOwnedReport(accountId, reportId);
        String normalizedTitle = normalizeTitle(title);
        requireNotFuture(examinationDate);
        if (examinationDate == null && report.getProcessingStatus() == ProcessingStatus.CONFIRMED
                && report.getReportKind() == ReportKind.STRUCTURED) {
            throw validation("examinationDate", "NotNull", "已确认的检验报告必须填写检查日期");
        }
        LocalDateTime now = now();
        if (reportMapper.updateMetadata(reportId, report.getVersion(), normalizedTitle, examinationDate, now) != 1) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT);
        }
        if (!Objects.equals(report.getExaminationDate(), examinationDate)) {
            observationMapper.updateFormalExaminationDate(reportId, examinationDate, now);
        }
        auditService.success(accountId, AuditAction.REPORT_METADATA_UPDATE, AuditService.token("report", reportId));
        report.setTitle(normalizedTitle);
        report.setExaminationDate(examinationDate);
        report.setVersion(report.getVersion() + 1);
        report.setUpdatedAt(now);
        return toView(report);
    }

    /**
     * 上传一张原图（可附带客户端压缩的预览图）。一次请求一个文件，由前端队列并发与逐个重试。
     */
    public PhotoUploadResult uploadPhoto(long accountId, long reportId, MultipartFile original,
                                         MultipartFile preview, Integer pageOrder, DuplicateDecision decision) {
        Report report = access.requireOwnedReport(accountId, reportId);
        if (report.getProcessingStatus() != ProcessingStatus.UPLOADING) {
            throw new ApiException(ErrorCode.REPORT_STATE_CONFLICT, "报告已提交识别，不能继续添加图片");
        }
        access.requireVerifiedEmail(accountId);

        if (original == null || original.isEmpty()) {
            throw validation("original", "NotNull", "请选择要上传的文件");
        }
        if (pageOrder == null || pageOrder < 1 || pageOrder > ReportPolicy.MAX_FILES_PER_REPORT) {
            throw validation("pageOrder", "Range", "页序必须在 1 到 " + ReportPolicy.MAX_FILES_PER_REPORT + " 之间");
        }
        requireWithinSizeLimit(original);
        DetectedMediaType originalType = detect(original);

        boolean hasPreview = preview != null && !preview.isEmpty();
        DetectedMediaType previewType = null;
        if (hasPreview) {
            if (!originalType.previewSupported()) {
                throw validation("preview", "NotAllowed", "该文件类型不接受预览图");
            }
            requireWithinSizeLimit(preview);
            previewType = detect(preview);
            if (!previewType.previewSupported()) {
                throw new ApiException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "预览图仅支持 JPEG、PNG");
            }
        }

        if (photoMapper.countActiveByReport(reportId) >= ReportPolicy.MAX_FILES_PER_REPORT) {
            throw new ApiException(ErrorCode.REPORT_FILE_LIMIT);
        }
        long incomingBytes = original.getSize() + (hasPreview ? preview.getSize() : 0L);
        if (photoMapper.sumActiveBytesByAccount(accountId) + incomingBytes > ReportPolicy.MAX_BYTES_PER_ACCOUNT) {
            throw new ApiException(ErrorCode.STORAGE_QUOTA_EXCEEDED);
        }

        String digest = sha256Hex(original);
        DuplicateDecision effectiveDecision = decision == null ? DuplicateDecision.ASK : decision;
        if (effectiveDecision == DuplicateDecision.ASK) {
            List<Long> existing = photoMapper.findReportIdsByDigest(report.getProfileId(), digest);
            if (!existing.isEmpty()) {
                return PhotoUploadResult.duplicate(existing);
            }
        }

        String baseKey = "profiles/" + report.getProfileId() + "/reports/" + reportId + "/"
                + UUID.randomUUID();
        LocalDateTime now = now();
        Photo photo = new Photo();
        photo.setReportId(reportId);
        photo.setProfileId(report.getProfileId());
        photo.setObjectKey(baseKey + extension(originalType));
        photo.setContentDigest(digest);
        photo.setMediaType(originalType.mimeType());
        photo.setByteSize(original.getSize());
        photo.setOriginalFilename(sanitizeFilename(original.getOriginalFilename()));
        if (hasPreview) {
            photo.setPreviewObjectKey(baseKey + "-preview" + extension(previewType));
            photo.setPreviewMediaType(previewType.mimeType());
            photo.setPreviewByteSize(preview.getSize());
        }
        photo.setPageOrder(pageOrder);
        photo.setStorageStatus(StorageStatus.PENDING);
        photo.setCreatedAt(now);
        photo.setUpdatedAt(now);
        try {
            photoMapper.insert(photo);
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.REPORT_STATE_CONFLICT, "该页已有图片，请刷新后重试");
        }

        try {
            store(photo.getObjectKey(), original, originalType);
            if (hasPreview) {
                store(photo.getPreviewObjectKey(), preview, previewType);
            }
        } catch (RuntimeException | IOException e) {
            // 不记录对象键以外的内容；对象键不含个人信息
            log.warn("图片写入存储失败，photoId={}", photo.getId(), e);
            deleteQuietly(photo.getObjectKey());
            if (hasPreview) {
                deleteQuietly(photo.getPreviewObjectKey());
            }
            photoMapper.updateStorageStatus(photo.getId(), StorageStatus.FAILED, now());
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "文件保存失败，请重试");
        }
        photoMapper.updateStorageStatus(photo.getId(), StorageStatus.COMPLETE, now());
        photo.setStorageStatus(StorageStatus.COMPLETE);
        auditService.success(accountId, AuditAction.REPORT_PHOTO_UPLOAD, AuditService.token("photo", photo.getId()));
        return PhotoUploadResult.uploaded(toView(photo));
    }

    // ---------------------------------------------------------------- helpers

    private static void requireWithinSizeLimit(MultipartFile file) {
        if (file.getSize() > ReportPolicy.MAX_FILE_BYTES) {
            throw new ApiException(ErrorCode.FILE_TOO_LARGE);
        }
    }

    private static DetectedMediaType detect(MultipartFile file) {
        byte[] header = new byte[DetectedMediaType.HEADER_BYTES];
        int length;
        try (InputStream in = file.getInputStream()) {
            length = in.readNBytes(header, 0, header.length);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.MALFORMED_REQUEST, "文件读取失败");
        }
        return DetectedMediaType.detect(header, length)
                .orElseThrow(() -> new ApiException(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
    }

    private static String sha256Hex(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException e) {
            throw new ApiException(ErrorCode.MALFORMED_REQUEST, "文件读取失败");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private void store(String key, MultipartFile file, DetectedMediaType type) throws IOException {
        try (InputStream in = file.getInputStream()) {
            storageService.put(key, in, file.getSize(), type.mimeType());
        }
    }

    private void deleteQuietly(String key) {
        try {
            storageService.delete(key);
        } catch (RuntimeException e) {
            log.warn("清理未完成的存储对象失败，需后续巡检处理");
        }
    }

    private static String extension(DetectedMediaType type) {
        return switch (type) {
            case JPEG -> ".jpg";
            case PNG -> ".png";
            case HEIC -> ".heic";
            case PDF -> ".pdf";
        };
    }

    private void requireNotFuture(LocalDate examinationDate) {
        if (examinationDate != null && examinationDate.isAfter(LocalDate.now(clock.withZone(ZoneOffset.ofHours(14))))) {
            // 以全球最早时区为界，避免用户本地“今天”被误判为未来
            throw validation("examinationDate", "PastOrPresent", "检查日期不能晚于今天");
        }
    }

    /**
     * 客户端原始文件名清洗（仅展示用）：去掉任何路径部分（/ 与 \），删除控制字符，去首尾空白，
     * 最多保留 255 个码点；结果为空返回 null。文件类型不依赖文件名。
     */
    static String sanitizeFilename(String raw) {
        if (raw == null) {
            return null;
        }
        int cut = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\'));
        String name = cut >= 0 ? raw.substring(cut + 1) : raw;
        StringBuilder sb = new StringBuilder(name.length());
        name.codePoints()
                .filter(cp -> !Character.isISOControl(cp)
                        && Character.getType(cp) != Character.FORMAT
                        && Character.getType(cp) != Character.LINE_SEPARATOR
                        && Character.getType(cp) != Character.PARAGRAPH_SEPARATOR)
                .forEach(sb::appendCodePoint);
        String cleaned = sb.toString().strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.codePointCount(0, cleaned.length()) > ORIGINAL_FILENAME_MAX) {
            cleaned = cleaned.substring(0, cleaned.offsetByCodePoints(0, ORIGINAL_FILENAME_MAX)).strip();
        }
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String trimmed = title.strip();
        if (trimmed.codePointCount(0, trimmed.length()) > ReportPolicy.TITLE_MAX_LENGTH) {
            throw validation("title", "Size", "标题不能超过 " + ReportPolicy.TITLE_MAX_LENGTH + " 个字符");
        }
        return trimmed;
    }

    private LocalDateTime now() {
        // DATETIME 列只保存到秒：截断后响应与库内值一致
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    private static ApiException validation(String field, String reason, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message,
                List.of(new FieldErrorItem(field, reason, message)));
    }

    /** 报告形态：缺省按结构化检验报告处理，非法取值按校验失败返回，不默默回退。 */
    private static ReportKind parseKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return ReportKind.STRUCTURED;
        }
        try {
            return ReportKind.valueOf(raw.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw validation("reportKind", "Enum", "报告形态只支持 STRUCTURED、IMAGING 或 UNSTRUCTURED");
        }
    }

    private static String normalizeConclusion(String conclusionText) {
        if (conclusionText == null || conclusionText.isBlank()) {
            return null;
        }
        String trimmed = conclusionText.strip();
        if (trimmed.codePointCount(0, trimmed.length()) > ReportPolicy.CONCLUSION_MAX_LENGTH) {
            throw validation("conclusionText", "Size",
                    "结论不能超过 " + ReportPolicy.CONCLUSION_MAX_LENGTH + " 个字符");
        }
        return trimmed;
    }

    private static ReportView toView(Report r) {
        return new ReportView(r.getId(), r.getProfileId(), r.getTitle(), r.getReportType(), r.getReportKind(),
                r.getExaminationDate(), r.getConclusionText(), r.getProcessingStatus(), r.getCreatedAt());
    }

    private static PhotoView toView(Photo p) {
        return new PhotoView(p.getId(), p.getReportId(), p.getPageOrder(), p.getMediaType(), p.getByteSize(),
                p.getPreviewObjectKey() != null);
    }
}
