package com.healthtrace.indicator.domain;

import java.time.LocalDate;

/**
 * “有数据的指标”列表的 SQL 聚合行：按 标准指标 code（未映射时再加报告原名）+ 单位/标本/方法的原始写法（逐字节）分组。
 * 单位写法归一、原名归一由服务层完成后再合并，保证与逐行计算结果一致。
 * firstPos/lastPos 为该组首/末行在“列表排序”（sort_order、原名、日期、id）中的序号，用于还原列表顺序与“最近一次”的展示写法。
 */
public class MetricAggregate {

    /** 已映射的标准指标 code；未映射为空。 */
    private String metricCode;
    /** 仅未映射行有意义（已映射行不按原名分组，取值任意）。 */
    private String originalName;
    private String unitText;
    private String specimenText;
    private String methodText;
    private long observationCount;
    private LocalDate latestDate;
    private long firstPos;
    private long lastPos;

    public String getMetricCode() {
        return metricCode;
    }

    public void setMetricCode(String metricCode) {
        this.metricCode = metricCode;
    }

    public String getOriginalName() {
        return originalName;
    }

    public void setOriginalName(String originalName) {
        this.originalName = originalName;
    }

    public String getUnitText() {
        return unitText;
    }

    public void setUnitText(String unitText) {
        this.unitText = unitText;
    }

    public String getSpecimenText() {
        return specimenText;
    }

    public void setSpecimenText(String specimenText) {
        this.specimenText = specimenText;
    }

    public String getMethodText() {
        return methodText;
    }

    public void setMethodText(String methodText) {
        this.methodText = methodText;
    }

    public long getObservationCount() {
        return observationCount;
    }

    public void setObservationCount(long observationCount) {
        this.observationCount = observationCount;
    }

    public LocalDate getLatestDate() {
        return latestDate;
    }

    public void setLatestDate(LocalDate latestDate) {
        this.latestDate = latestDate;
    }

    public long getFirstPos() {
        return firstPos;
    }

    public void setFirstPos(long firstPos) {
        this.firstPos = firstPos;
    }

    public long getLastPos() {
        return lastPos;
    }

    public void setLastPos(long lastPos) {
        this.lastPos = lastPos;
    }
}
