package com.healthtrace.rights.consent;

import org.apache.ibatis.annotations.Mapper;

/** CONSENT_RECORD 只追加。 */
@Mapper
public interface ConsentMapper {

    int insert(ConsentRecord record);
}
