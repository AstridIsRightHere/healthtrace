package com.healthtrace.report.mapper;

import com.healthtrace.report.domain.Report;
import com.healthtrace.report.domain.ReportSummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** REPORT 表访问。调用方须先完成档案归属校验，这里不做权限判断。 */
@Mapper
public interface ReportMapper {

    int insert(Report report);

    Report findById(@Param("id") long id);

    /** 待处理（DRAFT 别名）：visibility=NORMAL 且 processing_status≠CONFIRMED，按上传时间倒序。 */
    List<ReportSummary> findPendingByProfile(@Param("profileId") long profileId);
}
