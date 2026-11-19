package com.healthtrace.indicator.mapper;

import com.healthtrace.indicator.domain.MetricAlias;
import com.healthtrace.indicator.domain.MetricDefinition;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 指标字典访问（FR-018 管理员）。
 */
@Mapper
public interface MetricDefinitionMapper {

    List<MetricDefinition> findAll();

    MetricDefinition findByCode(@Param("code") String code);

    List<MetricAlias> findAliasesByCode(@Param("code") String code);
}
