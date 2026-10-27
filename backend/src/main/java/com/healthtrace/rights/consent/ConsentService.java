package com.healthtrace.rights.consent;

import com.healthtrace.profile.domain.Relationship;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 持久化同意记录（docs/19 CONSENT_RECORD）。加入调用方事务，与使用它的业务行同时提交或回滚。
 */
@Service
public class ConsentService {

    private final ConsentMapper consentMapper;

    public ConsentService(ConsentMapper consentMapper) {
        this.consentMapper = consentMapper;
    }

    /** 记录本次 OCR 发送第三方（百度）的特定同意，返回记录 id 供 ocr_job.consent_record_id 引用。 */
    public long grantOcr(long accountId, long profileId, Relationship relationship, LocalDateTime at) {
        return insert(accountId, profileId, relationship, ConsentPurpose.OCR_PROCESSING,
                ConsentPolicy.OCR_NOTICE_VERSION, ConsentPolicy.BAIDU_OCR_SCOPE, at);
    }

    /** 建档时的健康资料保存授权声明。 */
    public long grantStorage(long accountId, long profileId, Relationship relationship, String noticeVersion,
                             LocalDateTime at) {
        return insert(accountId, profileId, relationship, ConsentPurpose.HEALTH_DATA_STORAGE, noticeVersion, null, at);
    }

    private long insert(long accountId, long profileId, Relationship relationship, ConsentPurpose purpose,
                        String noticeVersion, String providerScope, LocalDateTime at) {
        ConsentRecord record = new ConsentRecord();
        record.setAccountId(accountId);
        record.setProfileId(profileId);
        record.setPurpose(purpose);
        record.setSubjectRole(SubjectRole.of(relationship));
        record.setNoticeVersion(noticeVersion);
        record.setDecision(ConsentDecision.GRANTED);
        record.setProviderScope(providerScope);
        record.setRecordedAt(at);
        consentMapper.insert(record);
        return record.getId();
    }
}
