package com.healthtrace.indicator.service;

import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.common.web.FieldErrorItem;
import com.healthtrace.indicator.domain.FollowPolicy;
import com.healthtrace.indicator.domain.MetricDefinition;
import com.healthtrace.indicator.domain.TempMetric;
import com.healthtrace.indicator.mapper.FollowedMetricMapper;
import com.healthtrace.indicator.mapper.TempMetricMapper;
import com.healthtrace.indicator.mapper.TrendMapper;
import com.healthtrace.indicator.service.FollowViews.FollowedMetricView;
import com.healthtrace.indicator.service.FollowViews.MetricDefinitionView;
import com.healthtrace.profile.mapper.ProfileMapper;
import com.healthtrace.rights.audit.AuditAction;
import com.healthtrace.rights.audit.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 档案关注指标（FR-009）。
 * <ul>
 *   <li>档案无 ACTIVE 授权时统一 404 HT-3001，不泄露存在性。</li>
 *   <li>保存时提交完整有序列表，整表替换（删除后按下标重插），批量关注、取消关注与拖拽排序共用一个入口。</li>
 *   <li>可关注标准字典中的指标与本档案的临时指标（"TMP:{id}"，他档案的临时指标视为未知）；空列表合法（全部取消关注）。</li>
 * </ul>
 */
@Service
public class FollowService {

    static final String FIELD = "metricCodes";

    private final FollowedMetricMapper followedMetricMapper;
    private final TrendMapper trendMapper;
    private final TempMetricMapper tempMetricMapper;
    private final ProfileMapper profileMapper;
    private final AuditService auditService;
    private final Clock clock;

    public FollowService(FollowedMetricMapper followedMetricMapper, TrendMapper trendMapper,
                         TempMetricMapper tempMetricMapper, ProfileMapper profileMapper,
                         AuditService auditService, Clock clock) {
        this.followedMetricMapper = followedMetricMapper;
        this.trendMapper = trendMapper;
        this.tempMetricMapper = tempMetricMapper;
        this.profileMapper = profileMapper;
        this.auditService = auditService;
        this.clock = clock;
    }

    public List<FollowedMetricView> list(long accountId, long profileId) {
        requireProfileAccess(accountId, profileId);
        return followedMetricMapper.findByProfile(profileId).stream().map(FollowedMetricView::from).toList();
    }

    /** 按字典顺序返回全部标准指标。字典为全局数据，只需登录。 */
    public List<MetricDefinitionView> listDefinitions() {
        return trendMapper.findAllDefinitions().stream().map(MetricDefinitionView::from).toList();
    }

    /** 整表替换关注列表；列表顺序即展示顺序。 */
    @Transactional
    public List<FollowedMetricView> replace(long accountId, long profileId, List<String> metricCodes) {
        requireProfileAccess(accountId, profileId);
        validate(profileId, metricCodes);

        followedMetricMapper.deleteByProfile(profileId);
        if (!metricCodes.isEmpty()) {
            LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
            followedMetricMapper.insertAll(profileId, metricCodes, now);
        }
        auditService.success(accountId, AuditAction.METRIC_FOLLOW_UPDATE, AuditService.token("profile", profileId));
        return followedMetricMapper.findByProfile(profileId).stream().map(FollowedMetricView::from).toList();
    }

    private void validate(long profileId, List<String> metricCodes) {
        if (metricCodes == null) {
            throw validation("NotNull", "请提交关注指标列表");
        }
        if (metricCodes.size() > FollowPolicy.MAX_FOLLOWED) {
            throw validation("Size", "最多关注 " + FollowPolicy.MAX_FOLLOWED + " 项指标");
        }
        Set<String> known = new HashSet<>();
        for (MetricDefinition definition : trendMapper.findAllDefinitions()) {
            known.add(definition.getCode());
        }
        for (TempMetric temp : tempMetricMapper.findKeysByProfile(profileId)) {
            known.add(TrendService.tempCode(temp.getId()));
        }
        Set<String> seen = new HashSet<>();
        for (String code : metricCodes) {
            if (code == null || !known.contains(code)) {
                throw validation("UnknownMetric", "存在未收录的指标：" + code);
            }
            if (!seen.add(code)) {
                throw validation("Duplicate", "指标重复：" + code);
            }
        }
    }

    private void requireProfileAccess(long accountId, long profileId) {
        if (profileMapper.findActiveByAccountAndProfile(accountId, profileId) == null) {
            throw new ApiException(ErrorCode.PROFILE_NOT_FOUND);
        }
    }

    private static ApiException validation(String reason, String message) {
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        return new ApiException(code, code.defaultMessage(), List.of(new FieldErrorItem(FIELD, reason, message)));
    }
}
