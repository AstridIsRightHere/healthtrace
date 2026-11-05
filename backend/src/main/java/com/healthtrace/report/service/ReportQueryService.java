package com.healthtrace.report.service;

import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.FieldErrorItem;
import com.healthtrace.common.web.ListPage;
import com.healthtrace.profile.mapper.ProfileMapper;
import com.healthtrace.report.domain.Photo;
import com.healthtrace.report.domain.ReportKind;
import com.healthtrace.report.domain.ReportListFilter;
import com.healthtrace.report.domain.ReportPolicy;
import com.healthtrace.report.domain.ReportSummary;
import com.healthtrace.report.domain.StorageStatus;
import com.healthtrace.report.mapper.PhotoMapper;
import com.healthtrace.report.mapper.ReportMapper;
import com.healthtrace.report.service.ReportViews.PhotoContent;
import com.healthtrace.report.service.ReportViews.PhotoVariant;
import com.healthtrace.report.service.ReportViews.ReportListItem;
import com.healthtrace.storage.StorageService;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 报告只读查询（FR-004、FR-010）：报告列表、已确认报告分组与图片读取。
 * <p>
 * 访问控制：任何 profileId / reportId / photoId 都先解析到档案，再要求当前账号持有该档案的 ACTIVE 授权；
 * 不存在与无权访问统一返回 404 HT-3001，避免暴露其他档案的资源是否存在。
 */
@Service
public class ReportQueryService {

    private final ReportAccess access;
    private final ReportMapper reportMapper;
    private final PhotoMapper photoMapper;
    private final ProfileMapper profileMapper;
    private final StorageService storageService;

    public ReportQueryService(ReportAccess access, ReportMapper reportMapper, PhotoMapper photoMapper,
                              ProfileMapper profileMapper, StorageService storageService) {
        this.access = access;
        this.reportMapper = reportMapper;
        this.photoMapper = photoMapper;
        this.profileMapper = profileMapper;
        this.storageService = storageService;
    }

    /**
     * 报告列表。支持 DRAFT（待处理）、CONFIRMED（已确认）与 REMOVED（回收站，FR-016）筛选；
     * kind 可选过滤（STRUCTURED/IMAGING/UNSTRUCTURED，不区分大小写）；
     * limit 为空表示全部，total 为忽略 limit 时满足筛选条件的总数（SQL LIMIT + 独立 COUNT）。
     */
    public ListPage<ReportListItem> listReports(long accountId, long profileId, ReportListFilter filter,
                                                String kind, Integer limit) {
        if (profileMapper.findActiveByAccountAndProfile(accountId, profileId) == null) {
            throw notFound();
        }
        // 非法 kind 直接按校验失败返回，避免静默返回空列表让前端以为“没有报告”
        String normalizedKind = normalizeKind(kind);
        return switch (filter) {
            case DRAFT -> ListPage.of(reportMapper.findPendingByProfile(profileId, normalizedKind, limit), limit,
                    () -> reportMapper.countPendingByProfile(profileId, normalizedKind), ReportQueryService::toListItem);
            case CONFIRMED -> ListPage.of(reportMapper.findConfirmedByProfile(profileId, normalizedKind, limit), limit,
                    () -> reportMapper.countConfirmedByProfile(profileId, normalizedKind), ReportQueryService::toListItem);
            case REMOVED -> ListPage.of(reportMapper.findRemovedByProfile(profileId, normalizedKind, limit), limit,
                    () -> reportMapper.countRemovedByProfile(profileId, normalizedKind), ReportQueryService::toListItem);
            case null -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "当前仅支持 status=DRAFT、CONFIRMED 或 REMOVED",
                    List.of(new FieldErrorItem("status", "NotSupported", "当前仅支持 status=DRAFT、CONFIRMED 或 REMOVED")));
        };
    }

    /** 已确认报告按种类聚合（标题清洗后归组，无标题按类型），供报告对比先选类型再选报告。 */
    public List<ReportViews.ReportGroup> listConfirmedGroups(long accountId, long profileId) {
        return ReportGrouping.group(listReports(accountId, profileId, ReportListFilter.CONFIRMED, null, null).items());
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
        access.requireOwnedReport(accountId, photo.getReportId());

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

    private static ApiException notFound() {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
    }

    /** 归一化 kind 查询参数：空表示不过滤，非法取值按校验失败返回（而非静默空列表）。 */
    private static String normalizeKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return null;
        }
        String upper = kind.strip().toUpperCase(Locale.ROOT);
        try {
            return ReportKind.valueOf(upper).name();
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "报告形态只支持 STRUCTURED、IMAGING 或 UNSTRUCTURED",
                    List.of(new FieldErrorItem("kind", "Enum", "报告形态只支持 STRUCTURED、IMAGING 或 UNSTRUCTURED")));
        }
    }

    private static ReportListItem toListItem(ReportSummary s) {
        return new ReportListItem(s.getId(), s.getTitle(), s.getReportType(), s.getReportKind(),
                s.getExaminationDate(), s.getConclusionText(), s.getProcessingStatus(), s.getCreatedAt(),
                s.getPhotoCount(), s.getCoverPhotoId(), s.getCoverHasPreview(), s.getLastOcrErrorCode(),
                s.getRemovedAt());
    }
}
