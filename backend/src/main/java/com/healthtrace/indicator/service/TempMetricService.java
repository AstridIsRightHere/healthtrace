package com.healthtrace.indicator.service;

import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.FieldErrorItem;
import com.healthtrace.indicator.domain.FollowPolicy;
import com.healthtrace.indicator.domain.TempMetric;
import com.healthtrace.indicator.domain.TempMetricCandidate;
import com.healthtrace.indicator.domain.TempMetricMappingStatus;
import com.healthtrace.indicator.mapper.FollowedMetricMapper;
import com.healthtrace.indicator.mapper.TempMetricMapper;
import com.healthtrace.indicator.service.TempMetricViews.TempMetricView;
import com.healthtrace.profile.mapper.ProfileMapper;
import com.healthtrace.report.domain.ObservationStatus;
import com.healthtrace.report.domain.ProcessingStatus;
import com.healthtrace.report.domain.VisibilityStatus;
import com.healthtrace.rights.audit.AuditAction;
import com.healthtrace.rights.audit.AuditService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 临时指标（FR-009，docs/31 §5，DEC-015）。
 * <ul>
 *   <li>默认不建：只有用户对已确认、正常可见报告中未映射标准字典的正式结果主动“设为临时指标”才在本档案内创建。</li>
 *   <li>归并键 = 归一后的报告原名 + 归一后的单位写法；同档案同键自动合并为一个临时指标，跨档案永不合并；
 *       创建或复用时把本档案全部同键、尚未映射的正式结果一并归入。</li>
 *   <li>新确认 / 修改的报告中同键结果自动归入已有临时指标（{@link #assignReport}），标准指标映射优先。</li>
 *   <li>删除临时指标只解除映射与关注，结果行本身不变，回到“其他项目”。</li>
 *   <li>档案无 ACTIVE 授权、结果或临时指标不属于该档案时统一 404 HT-3001。</li>
 * </ul>
 */
@Service
public class TempMetricService {

    static final int NAME_MAX = 160;
    static final int UNIT_MAX = 80;

    private final TempMetricMapper tempMetricMapper;
    private final FollowedMetricMapper followedMetricMapper;
    private final ProfileMapper profileMapper;
    private final AuditService auditService;
    private final Clock clock;

    public TempMetricService(TempMetricMapper tempMetricMapper, FollowedMetricMapper followedMetricMapper,
                             ProfileMapper profileMapper, AuditService auditService, Clock clock) {
        this.tempMetricMapper = tempMetricMapper;
        this.followedMetricMapper = followedMetricMapper;
        this.profileMapper = profileMapper;
        this.auditService = auditService;
        this.clock = clock;
    }

    public List<TempMetricView> list(long accountId, long profileId) {
        requireProfileAccess(accountId, profileId);
        return tempMetricMapper.findByProfile(profileId).stream().map(TempMetricView::from).toList();
    }

    /** 设为临时指标；重复提交幂等（已归入时直接返回，follow=true 时补加关注）。 */
    @Transactional
    public TempMetricView create(long accountId, long profileId, Long observationId, Boolean follow) {
        requireProfileAccess(accountId, profileId);
        if (observationId == null) {
            throw validation("observationId", "NotNull", "请指定检查结果");
        }
        TempMetricCandidate candidate = tempMetricMapper.findCandidate(observationId);
        if (candidate == null || !Objects.equals(candidate.getProfileId(), profileId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (candidate.getStatus() != ObservationStatus.FORMAL
                || candidate.getProcessingStatus() != ProcessingStatus.CONFIRMED
                || candidate.getVisibilityStatus() != VisibilityStatus.NORMAL) {
            throw validation("observationId", "NotConfirmed", "只有已确认报告中的结果可以设为临时指标");
        }
        if (candidate.getMetricDefinitionId() != null) {
            throw validation("observationId", "StandardMetric", "该结果已对应标准指标，无需设为临时指标");
        }
        LocalDateTime now = now();

        long tempMetricId;
        if (candidate.getTempMetricId() != null) {
            tempMetricId = candidate.getTempMetricId();
        } else {
            String nameKey = TrendService.normalizeName(candidate.getOriginalName());
            if (nameKey.isEmpty() || nameKey.length() > NAME_MAX) {
                throw validation("observationId", "InvalidName", "该结果没有可用的项目名称，不能设为临时指标");
            }
            String unitKey = UnitNormalizer.normalize(candidate.getUnitText());
            TempMetric existing = tempMetricMapper.findByKey(profileId, nameKey, unitKey);
            boolean created = existing == null;
            tempMetricId = created ? insert(profileId, candidate, nameKey, unitKey, now) : existing.getId();
            int assigned = assignMatching(profileId, tempMetricId, nameKey, unitKey, now);
            if (!created || assigned > 1) {
                tempMetricMapper.markMerged(tempMetricId, now);
            }
            auditService.success(accountId, AuditAction.TEMP_METRIC_CREATE,
                    AuditService.token("tempMetric", tempMetricId));
        }

        if (Boolean.TRUE.equals(follow)) {
            TempMetric current = tempMetricMapper.findById(profileId, tempMetricId);
            if (current != null && !current.isFollowed()) {
                if (followedMetricMapper.countByProfile(profileId) >= FollowPolicy.MAX_FOLLOWED) {
                    throw validation("follow", "Size", "最多关注 " + FollowPolicy.MAX_FOLLOWED + " 项指标");
                }
                followedMetricMapper.appendTempMetric(profileId, tempMetricId, now);
                auditService.success(accountId, AuditAction.METRIC_FOLLOW_UPDATE,
                        AuditService.token("profile", profileId));
            }
        }
        return TempMetricView.from(tempMetricMapper.findById(profileId, tempMetricId));
    }

    /** 删除临时指标：先删关注、解除结果映射，再删临时指标本身（外键顺序）。 */
    @Transactional
    public void delete(long accountId, long profileId, long tempMetricId) {
        requireProfileAccess(accountId, profileId);
        if (tempMetricMapper.findById(profileId, tempMetricId) == null) {
            throw new ApiException(ErrorCode.TEMP_METRIC_NOT_FOUND);
        }
        followedMetricMapper.deleteByTempMetric(tempMetricId);
        tempMetricMapper.unassignObservations(tempMetricId);
        tempMetricMapper.deleteById(profileId, tempMetricId);
        auditService.success(accountId, AuditAction.TEMP_METRIC_DELETE, AuditService.token("tempMetric", tempMetricId));
    }

    /**
     * 报告确认 / 修改后调用（调用方事务内）：本报告中尚未映射的正式结果，若与本档案已有临时指标同键，则自动归入；
     * 不创建新的临时指标。须在标准字典映射（assignMetricDefinitions）之后调用，标准指标优先。
     */
    public int assignReport(long profileId, long reportId) {
        List<TempMetricCandidate> rows = tempMetricMapper.findUnassignedFormalByReport(reportId);
        if (rows.isEmpty()) {
            return 0;
        }
        Map<String, Long> byKey = new HashMap<>();
        for (TempMetric t : tempMetricMapper.findKeysByProfile(profileId)) {
            byKey.put(key(t.getNameKey(), t.getUnitKey()), t.getId());
        }
        if (byKey.isEmpty()) {
            return 0;
        }
        Map<Long, List<Long>> grouped = new HashMap<>();
        for (TempMetricCandidate c : rows) {
            Long id = byKey.get(key(TrendService.normalizeName(c.getOriginalName()),
                    UnitNormalizer.normalize(c.getUnitText())));
            if (id != null) {
                grouped.computeIfAbsent(id, k -> new ArrayList<>()).add(c.getObservationId());
            }
        }
        LocalDateTime now = now();
        int assigned = 0;
        for (Map.Entry<Long, List<Long>> e : grouped.entrySet()) {
            int n = tempMetricMapper.assignObservations(e.getKey(), e.getValue(), now);
            if (n > 0) {
                tempMetricMapper.markMerged(e.getKey(), now);
            }
            assigned += n;
        }
        return assigned;
    }

    // ---------------------------------------------------------------- helpers

    private long insert(long profileId, TempMetricCandidate candidate, String nameKey, String unitKey,
                        LocalDateTime now) {
        TempMetric metric = new TempMetric();
        metric.setProfileId(profileId);
        metric.setDisplayName(truncate(TrendService.displayName(candidate.getOriginalName()), NAME_MAX));
        metric.setNameKey(nameKey);
        metric.setUnitKey(unitKey);
        String unit = candidate.getUnitText() == null || candidate.getUnitText().isBlank()
                ? null : truncate(candidate.getUnitText().trim(), UNIT_MAX);
        metric.setUnitText(unit);
        metric.setOriginObservationId(candidate.getObservationId());
        metric.setMappingStatus(TempMetricMappingStatus.UNMERGED.name());
        metric.setCreatedAt(now);
        metric.setUpdatedAt(now);
        try {
            tempMetricMapper.insert(metric);
            return metric.getId();
        } catch (DuplicateKeyException e) {
            // 并发创建同键临时指标：uk_temp_metric_key 兜底，改为复用对方创建的行
            TempMetric raced = tempMetricMapper.findByKey(profileId, nameKey, unitKey);
            if (raced == null) {
                throw e;
            }
            return raced.getId();
        }
    }

    private int assignMatching(long profileId, long tempMetricId, String nameKey, String unitKey,
                               LocalDateTime now) {
        List<Long> ids = new ArrayList<>();
        for (TempMetricCandidate c : tempMetricMapper.findUnassignedFormalByProfile(profileId)) {
            if (nameKey.equals(TrendService.normalizeName(c.getOriginalName()))
                    && unitKey.equals(UnitNormalizer.normalize(c.getUnitText()))) {
                ids.add(c.getObservationId());
            }
        }
        return ids.isEmpty() ? 0 : tempMetricMapper.assignObservations(tempMetricId, ids, now);
    }

    private static String key(String nameKey, String unitKey) {
        return nameKey + "\u0000" + unitKey;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    private void requireProfileAccess(long accountId, long profileId) {
        if (profileMapper.findActiveByAccountAndProfile(accountId, profileId) == null) {
            throw new ApiException(ErrorCode.PROFILE_NOT_FOUND);
        }
    }

    private static ApiException validation(String field, String reason, String message) {
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        return new ApiException(code, code.defaultMessage(), List.of(new FieldErrorItem(field, reason, message)));
    }
}
