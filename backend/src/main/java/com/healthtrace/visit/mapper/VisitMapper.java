package com.healthtrace.visit.mapper;

import com.healthtrace.visit.domain.LinkedReport;
import com.healthtrace.visit.domain.Visit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/** VISIT / VISIT_REPORT 表访问。调用方须先完成档案归属校验，这里不做权限判断。 */
@Mapper
public interface VisitMapper {

    int insert(Visit visit);

    Visit findById(@Param("id") long id);

    /** 按就诊日倒序、id 倒序。limit 为空表示全部。 */
    List<Visit> findByProfile(@Param("profileId") long profileId, @Param("limit") Integer limit);

    /** 档案下就诊总数（忽略 limit）。 */
    long countByProfile(@Param("profileId") long profileId);

    /** 乐观锁更新可编辑字段：version 不符时返回 0。 */
    int update(@Param("visit") Visit visit, @Param("expectedVersion") long expectedVersion);

    /** 删除就诊；关联行由外键 ON DELETE CASCADE 删除，报告本身不受影响。 */
    int delete(@Param("id") long id);

    /** 幂等关联：已存在时不重复插入，返回受影响行数。 */
    int link(@Param("visitId") long visitId, @Param("reportId") long reportId,
             @Param("createdAt") LocalDateTime createdAt);

    int unlink(@Param("visitId") long visitId, @Param("reportId") long reportId);

    /** 解除报告与全部就诊的关联（报告彻底删除前显式清理，不依赖级联）。 */
    int unlinkReport(@Param("reportId") long reportId);

    /** 批量读取关联报告（只含 visibility=NORMAL），按检查日期倒序。 */
    List<LinkedReport> findLinkedReports(@Param("visitIds") Collection<Long> visitIds);
}
