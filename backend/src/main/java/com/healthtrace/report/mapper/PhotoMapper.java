package com.healthtrace.report.mapper;

import com.healthtrace.report.domain.Photo;
import com.healthtrace.report.domain.StorageStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** PHOTO 表访问。调用方须先完成档案归属校验。 */
@Mapper
public interface PhotoMapper {

    /** 违反 uk_photo_report_page（同报告同页序已有待上传/完整图片）时抛 DuplicateKeyException。 */
    int insert(Photo photo);

    Photo findById(@Param("id") long id);

    int updateStorageStatus(@Param("id") long id,
                            @Param("status") StorageStatus status,
                            @Param("updatedAt") LocalDateTime updatedAt);

    /** 本报告占用名额的文件数（待上传 + 完整）。 */
    int countActiveByReport(@Param("reportId") long reportId);

    /** 该账号上传的、仍占用空间的原件 + 预览图字节合计。 */
    long sumActiveBytesByAccount(@Param("accountId") long accountId);

    /** 报告内全部完整图片，按页序。 */
    List<Photo> findCompleteByReport(@Param("reportId") long reportId);

    /** 同档案内内容摘要相同的完整图片所在报告（去重，FR-005 只提示不合并）。 */
    List<Long> findReportIdsByDigest(@Param("profileId") long profileId,
                                     @Param("contentDigest") String contentDigest);

    /** 报告下全部图片（任意存储状态）的对象键，用于彻底删除后清理存储。 */
    List<Photo> findAllKeysByReport(@Param("reportId") long reportId);

    int deleteByReport(@Param("reportId") long reportId);
}
