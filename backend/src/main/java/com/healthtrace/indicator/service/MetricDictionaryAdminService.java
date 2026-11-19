package com.healthtrace.indicator.service;

import com.healthtrace.account.api.admin.MetricDictionaryAdminController;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import com.healthtrace.indicator.domain.MetricAlias;
import com.healthtrace.indicator.domain.MetricDefinition;
import com.healthtrace.indicator.mapper.MetricDefinitionMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 管理员：指标字典查询（FR-018）。
 */
@Service
public class MetricDictionaryAdminService {

    private final MetricDefinitionMapper metricDefinitionMapper;

    public MetricDictionaryAdminService(MetricDefinitionMapper metricDefinitionMapper) {
        this.metricDefinitionMapper = metricDefinitionMapper;
    }

    /**
     * 列出所有标准指标。
     */
    public List<MetricDictionaryAdminController.MetricDefinitionView> listAll() {
        return metricDefinitionMapper.findAll().stream()
                .map(MetricDictionaryAdminController.MetricDefinitionView::from)
                .toList();
    }

    /**
     * 查看单个指标详情，包含别名。
     */
    public MetricDictionaryAdminController.MetricDefinitionDetailView getDetail(String code) {
        MetricDefinition def = metricDefinitionMapper.findByCode(code);
        if (def == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "指标不存在");
        }

        List<MetricAlias> aliases = metricDefinitionMapper.findAliasesByCode(code);
        List<MetricDictionaryAdminController.AliasView> aliasViews = aliases.stream()
                .map(a -> new MetricDictionaryAdminController.AliasView(
                        a.getId(),
                        a.getAlias(),
                        a.getRequiredUnit()
                ))
                .toList();

        return new MetricDictionaryAdminController.MetricDefinitionDetailView(
                def.getCode(),
                def.getDictionaryId(),
                def.getNationalCode(),
                def.getStandardName(),
                def.getStandardUnit(),
                def.getReportType(),
                def.getComparabilityNote(),
                def.getSortOrder(),
                aliasViews
        );
    }
}
