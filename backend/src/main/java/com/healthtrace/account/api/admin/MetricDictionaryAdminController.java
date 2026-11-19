package com.healthtrace.account.api.admin;

import com.healthtrace.indicator.domain.MetricDefinition;
import com.healthtrace.indicator.service.MetricDictionaryAdminService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 管理员：指标字典管理（FR-018）。
 * 查看 18 项标准指标，编辑别名（未来扩展）。
 */
@RestController
@RequestMapping("/api/v1/admin/metric-dictionary")
public class MetricDictionaryAdminController {

    private final MetricDictionaryAdminService metricDictionaryAdminService;

    public MetricDictionaryAdminController(MetricDictionaryAdminService metricDictionaryAdminService) {
        this.metricDictionaryAdminService = metricDictionaryAdminService;
    }

    /**
     * 列出所有标准指标（GET /api/v1/admin/metric-dictionary）。
     */
    @GetMapping
    public List<MetricDefinitionView> listMetricDefinitions() {
        return metricDictionaryAdminService.listAll();
    }

    /**
     * 查看单个指标详情，包含别名（GET /api/v1/admin/metric-dictionary/{code}）。
     */
    @GetMapping("/{code}")
    public MetricDefinitionDetailView getMetricDefinition(@PathVariable String code) {
        return metricDictionaryAdminService.getDetail(code);
    }

    public record MetricDefinitionView(
            String code,
            String dictionaryId,
            String nationalCode,
            String standardName,
            String standardUnit,
            String reportType,
            String comparabilityNote,
            int sortOrder
    ) {
        public static MetricDefinitionView from(MetricDefinition def) {
            return new MetricDefinitionView(
                    def.getCode(),
                    def.getDictionaryId(),
                    def.getNationalCode(),
                    def.getStandardName(),
                    def.getStandardUnit(),
                    def.getReportType(),
                    def.getComparabilityNote(),
                    def.getSortOrder()
            );
        }
    }

    public record MetricDefinitionDetailView(
            String code,
            String dictionaryId,
            String nationalCode,
            String standardName,
            String standardUnit,
            String reportType,
            String comparabilityNote,
            int sortOrder,
            List<AliasView> aliases
    ) {
    }

    public record AliasView(
            long id,
            String alias,
            String requiredUnit
    ) {
    }
}
