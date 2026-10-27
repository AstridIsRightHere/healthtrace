package com.healthtrace.report.mapper;

import com.healthtrace.report.domain.ReportRevision;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** REPORT_REVISION 只追加：不提供更新与删除。 */
@Mapper
public interface ReportRevisionMapper {

    int insert(ReportRevision revision);

    /** 按版本倒序（最新修改在前）。 */
    List<ReportRevision> findByReport(@Param("reportId") long reportId);
}
