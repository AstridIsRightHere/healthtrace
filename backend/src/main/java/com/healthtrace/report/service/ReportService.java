package com.healthtrace.report.service;

import com.healthtrace.account.domain.Account;
import com.healthtrace.account.mapper.AccountMapper;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.FieldErrorItem;
import com.healthtrace.profile.mapper.ProfileMapper;
import com.healthtrace.report.domain.DetectedMediaType;
import com.healthtrace.report.domain.DuplicateDecision;
import com.healthtrace.report.domain.Photo;
import com.healthtrace.report.domain.ProcessingStatus;
import com.healthtrace.report.domain.Report;
import com.healthtrace.report.domain.ReportListFilter;
import com.healthtrace.report.domain.ReportPolicy;
import com.healthtrace.report.domain.ReportSummary;
import com.healthtrace.report.domain.ReportType;
import com.healthtrace.report.domain.StorageStatus;
import com.healthtrace.report.domain.VisibilityStatus;
import com.healthtrace.report.mapper.PhotoMapper;
import com.healthtrace.report.mapper.ReportMapper;
import com.healthtrace.report.service.ReportViews.PhotoContent;
import com.healthtrace.report.service.ReportViews.PhotoUploadResult;
import com.healthtrace.report.service.ReportViews.PhotoVariant;
import com.healthtrace.report.service.ReportViews.PhotoView;
import com.healthtrace.report.service.ReportViews.ReportListItem;
import com.healthtrace.report.service.ReportViews.ReportView;
import com.healthtrace.rights.audit.AuditAction;
import com.healthtrace.rights.audit.AuditService;
import com.healthtrace.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 报告草稿与图片上传（FR-004、FR-005，DEC-008）。
 * <p>
 * 访问控制：任何 reportId / photoId 都先解析到 profileId，再要求当前账号持有该档案的 ACTIVE 授权；
 * 不存在与无权访问统一返回 404 HT-3001，避免暴露其他档案的资源是否存在。
 * <p>
 * 一致性：图片先以 PENDING 落库占用页序 → 写入存储 → 标记 COMPLETE；写入失败则删除已写对象并标记 FAILED。
 * 页序 1..20 + 唯一约束 uk_photo_report_page 在并发下兜底“单报告最多 20 个文件”。
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final ReportMapper reportMapper;
    private final PhotoMapper photoMapper;
    private final ProfileMapper profileMapper;
    private final AccountMapper accountMapper;
    private final StorageService storageService;
    private final AuditService auditService;
    private final Clock clock;

    public ReportService(ReportMapper reportMapper, PhotoMapper photoMapper, ProfileMapper profileMapper,
                         AccountMapper accountMapper, StorageService storageService, AuditService auditService,
                         Clock clock) {
        this.reportMapper = reportMapper;
        this.photoMapper = photoMapper;
        this.profileMapper = profileMapper;
        this.accountMapper = accountMapper;
        this.storageService = storageService;
        this.auditService = auditService;
        this.clock = clock;
    }

    /** 新建报告草稿（UPLOADING）。标题、检查日期可选，可在确认阶段再补。 */
    public ReportView createReport(long accountId, long profileId, String title, LocalDate examinationDate) {
        requireProfileAccess(accountId, profileId);
        requireVerifiedEmail(accountId);
        String normalizedTitle = normalizeTitle(title);
        if (examinationDate != null && examinationDate.isAfter(LocalDate.now(clock.withZone(ZoneOffset.ofHours(14))))) {
            // 以全球最早时区为界，避免用户本地“今天”被误判为未来
            throw validation("examinationDate", "PastOrPresent", "检查日期不能晚于今天");
        }

        LocalDateTime now = now();
        Report report = new Report();
        report.setProfileId(profileId);
        report.setCreatedBy(accountId);
        report.setTitle(normalizedTitle);
        report.setReportType(ReportType.UNKNOWN);
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
     * 上传一张原图（可附带客户端压缩的预览图）。一次请求一个文件，由前端队列并发与逐个重试。
     */
    public PhotoUploadResult uploadPhoto(long accountId, long reportId, MultipartFile original,
                                         MultipartFile preview, Integer pageOrder, DuplicateDecision decision) {
        Report report = requireOwnedReport(accountId, reportId);
        if (report.getProcessingStatus() != ProcessingStatus.UPLOADING) {
            throw new ApiException(ErrorCode.REPORT_STATE_CONFLICT, "报告已提交识别，不能继续添加图片");
        }
        requireVerifiedEmail(accountId);

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

    /** 报告列表。支持 DRAFT（待处理）与 CONFIRMED（已确认）筛选。 */
    public List<ReportListItem> listReports(long accountId, long profileId, ReportListFilter filter) {
        requireProfileAccess(accountId, profileId);
        List<ReportSummary> rows = switch (filter) {
            case DRAFT -> reportMapper.findPendingByProfile(profileId);
            case CONFIRMED -> reportMapper.findConfirmedByProfile(profileId);
            case null -> throw validation("status", "NotSupported", "当前仅支持 status=DRAFT 或 CONFIRMED");
        };
        return rows.stream().map(ReportService::toListItem).toList();
    }

    /** 已确认报告按种类聚合（标题清洗后归组，无标题按类型），供报告对比先选类型再选报告。 */
    public List<ReportViews.ReportGroup> listConfirmedGroups(long accountId, long profileId) {
        return ReportGrouping.group(listReports(accountId, profileId, ReportListFilter.CONFIRMED));
    }

    /**
     * 读取图片。支持签名直链的存储返回跳转地址；本地存储由应用回传内容。
     * 请求预览图但该图片没有预览（HEIC/PDF）时视为不存在，前端显示占位图标。
     */
    public PhotoContent openPhoto(long accountId, long photoId, PhotoVariant variant) {
        Photo photo = photoMapper.findById(photoId);
        if (photo == null || photo.getStorageStatus() != StorageStatus.COMPLETE) {
            throw notFound();
        }
        requireOwnedReport(accountId, photo.getReportId());

        String key;
        String mediaType;
        long byteSize;
        if (variant == PhotoVariant.PREVIEW) {
            if (photo.getPreviewObjectKey() == null) {
                throw notFound();
            }
            key = photo.getPreviewObjectKey();
            mediaType = photo.getPreviewMediaType();
            byteSize = photo.getPreviewByteSize();
        } else {
            key = photo.getObjectKey();
            mediaType = photo.getMediaType();
            byteSize = photo.getByteSize();
        }

        Optional<URI> url = storageService.createAccessUrl(key, ReportPolicy.ACCESS_URL_TTL);
        if (url.isPresent()) {
            return new PhotoContent(url.get(), null, mediaType, byteSize);
        }
        return new PhotoContent(null, storageService.open(key), mediaType, byteSize);
    }

    // ---------------------------------------------------------------- helpers

    private void requireProfileAccess(long accountId, long profileId) {
        if (profileMapper.findActiveByAccountAndProfile(accountId, profileId) == null) {
            throw notFound();
        }
    }

    private Report requireOwnedReport(long accountId, long reportId) {
        Report report = reportMapper.findById(reportId);
        if (report == null || report.getVisibilityStatus() != VisibilityStatus.NORMAL) {
            throw notFound();
        }
        requireProfileAccess(accountId, report.getProfileId());
        return report;
    }

    /** US-001：未验证邮箱的账号不能进行真实上传。 */
    private void requireVerifiedEmail(long accountId) {
        Account account = accountMapper.findById(accountId);
        if (account == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        if (account.getEmailVerifiedAt() == null) {
            throw new ApiException(ErrorCode.EMAIL_NOT_VERIFIED);
        }
    }

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

    private static ApiException notFound() {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
    }

    private static ApiException validation(String field, String reason, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message,
                List.of(new FieldErrorItem(field, reason, message)));
    }

    private static ReportView toView(Report r) {
        return new ReportView(r.getId(), r.getProfileId(), r.getTitle(), r.getReportType(), r.getExaminationDate(),
                r.getProcessingStatus(), r.getCreatedAt());
    }

    private static PhotoView toView(Photo p) {
        return new PhotoView(p.getId(), p.getReportId(), p.getPageOrder(), p.getMediaType(), p.getByteSize(),
                p.getPreviewObjectKey() != null);
    }

    private static ReportListItem toListItem(ReportSummary s) {
        return new ReportListItem(s.getId(), s.getTitle(), s.getReportType(), s.getExaminationDate(),
                s.getProcessingStatus(), s.getCreatedAt(), s.getPhotoCount(), s.getCoverPhotoId(),
                s.getCoverHasPreview(), s.getLastOcrErrorCode());
    }
}
