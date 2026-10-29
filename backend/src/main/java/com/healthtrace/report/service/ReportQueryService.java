package com.healthtrace.report.service;

import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.FieldErrorItem;
import com.healthtrace.common.web.ListPage;
import com.healthtrace.profile.mapper.ProfileMapper;
import com.healthtrace.report.domain.Photo;
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
     * 报告列表。支持 DRAFT（待处理）与 CONFIRMED（已确认）筛选；limit 为空表示全部，
     * total 为忽略 limit 时满足筛选条件的总数（SQL LIMIT + 独立 COUNT）。
     */
    public ListPage<ReportListItem> listReports(long accountId, long profileId, ReportListFilter filter,
                                                Integer limit) {
        if (profileMapper.findActiveByAccountAndProfile(accountId, profileId) == null) {
            throw notFound();
        }
        return switch (filter) {
            case DRAFT -> ListPage.of(reportMapper.findPendingByProfile(profileId, limit), limit,
                    () -> reportMapper.countPendingByProfile(profileId), ReportQueryService::toListItem);
            case CONFIRMED -> ListPage.of(reportMapper.findConfirmedByProfile(profileId, limit), limit,
                    () -> reportMapper.countConfirmedByProfile(profileId), ReportQueryService::toListItem);
            case null -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "当前仅支持 status=DRAFT 或 CONFIRMED",
                    List.of(new FieldErrorItem("status", "NotSupported", "当前仅支持 status=DRAFT 或 CONFIRMED")));
        };
    }

    /** 已确认报告按种类聚合（标题清洗后归组，无标题按类型），供报告对比先选类型再选报告。 */
    public List<ReportViews.ReportGroup> listConfirmedGroups(long accountId, long profileId) {
        return ReportGrouping.group(listReports(accountId, profileId, ReportListFilter.CONFIRMED, null).items());
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

    private static ReportListItem toListItem(ReportSummary s) {
        return new ReportListItem(s.getId(), s.getTitle(), s.getReportType(), s.getExaminationDate(),
                s.getProcessingStatus(), s.getCreatedAt(), s.getPhotoCount(), s.getCoverPhotoId(),
                s.getCoverHasPreview(), s.getLastOcrErrorCode());
    }
}
