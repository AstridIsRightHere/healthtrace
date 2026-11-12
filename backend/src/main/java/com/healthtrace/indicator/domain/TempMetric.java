package com.healthtrace.indicator.domain;

import java.time.LocalDateTime;

/**
 * TEMP_METRIC：本档案内的临时指标（FR-009，DEC-015）。
 * 归并键 = 归一后的报告原名（nameKey）+ 归一后的单位写法（unitKey，无单位为空串）；同档案同键只有一个临时指标。
 * observationCount / followed 仅在列表查询中填充。
 */
public class TempMetric {

    private Long id;
    private Long profileId;
    private String displayName;
    private String nameKey;
    private String unitKey;
    private String unitText;
    private Long originObservationId;
    private String mappingStatus;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 已确认、正常可见报告中映射到该临时指标的正式结果数。 */
    private long observationCount;
    private boolean followed;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProfileId() { return profileId; }
    public void setProfileId(Long profileId) { this.profileId = profileId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getNameKey() { return nameKey; }
    public void setNameKey(String nameKey) { this.nameKey = nameKey; }
    public String getUnitKey() { return unitKey; }
    public void setUnitKey(String unitKey) { this.unitKey = unitKey; }
    public String getUnitText() { return unitText; }
    public void setUnitText(String unitText) { this.unitText = unitText; }
    public Long getOriginObservationId() { return originObservationId; }
    public void setOriginObservationId(Long originObservationId) { this.originObservationId = originObservationId; }
    public String getMappingStatus() { return mappingStatus; }
    public void setMappingStatus(String mappingStatus) { this.mappingStatus = mappingStatus; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public long getObservationCount() { return observationCount; }
    public void setObservationCount(long observationCount) { this.observationCount = observationCount; }
    public boolean isFollowed() { return followed; }
    public void setFollowed(boolean followed) { this.followed = followed; }
}
