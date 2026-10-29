package com.healthtrace.rights.audit;

import org.apache.ibatis.annotations.Mapper;

/** AUDIT_EVENT 只追加：不提供更新与删除。 */
@Mapper
public interface AuditMapper {

    int insert(AuditEvent event);
}
