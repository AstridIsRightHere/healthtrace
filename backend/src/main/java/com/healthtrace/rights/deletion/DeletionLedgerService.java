package com.healthtrace.rights.deletion;

import com.healthtrace.common.security.SecretKeyProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;

/**
 * 删除账本（docs/06 删除账本、docs/18 删除事件重放）。
 * <ul>
 *   <li>必须在业务删除事务内调用：业务回滚时账本一并回滚，不会出现未发生的删除记录。</li>
 *   <li>subject_digest = HMAC-SHA256("report:42" / "account:7")，密钥由邮箱索引密钥按用途派生，
 *       账本中没有明文 id；持有密钥的运维可按 id 重算摘要核对删除是否发生。</li>
 * </ul>
 */
@Service
public class DeletionLedgerService {

    private static final String HMAC = "HmacSHA256";
    /** 用途分离：派生密钥 = HMAC(邮箱索引密钥, 此标签)，与邮箱索引互不可推。 */
    private static final byte[] KEY_LABEL = "healthtrace/deletion-ledger/v1".getBytes(StandardCharsets.UTF_8);

    private final DeletionMapper deletionMapper;
    private final StoragePurgeService purgeService;
    private final Clock clock;
    private final SecretKeySpec digestKey;

    public DeletionLedgerService(DeletionMapper deletionMapper, StoragePurgeService purgeService, Clock clock,
                                 SecretKeyProperties keys) {
        this.deletionMapper = deletionMapper;
        this.purgeService = purgeService;
        this.clock = clock;
        this.digestKey = new SecretKeySpec(hmac(new SecretKeySpec(keys.emailIndexKeyBytes(), HMAC), KEY_LABEL), HMAC);
    }

    /** 记录一次已完成的删除（无存储对象待清理）。返回账本 id。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public long record(DeletionEventType type, long subjectId, int itemCount) {
        return recordWithStorage(type, subjectId, itemCount, List.of());
    }

    /**
     * 记录删除并登记待清理的存储对象：有对象时账本为 STORAGE_PENDING，对象全部删除后转为 COMPLETED。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long recordWithStorage(DeletionEventType type, long subjectId, int itemCount,
                                  Collection<String> objectKeys) {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        boolean pending = objectKeys.stream().anyMatch(k -> k != null && !k.isBlank());
        DeletionLedgerEntry entry = new DeletionLedgerEntry();
        entry.setEventType(type);
        entry.setSubjectDigest(digest(type, subjectId));
        entry.setItemCount(itemCount);
        entry.setStatus(pending ? DeletionLedgerEntry.STORAGE_PENDING : DeletionLedgerEntry.COMPLETED);
        entry.setRequestedAt(now);
        entry.setCompletedAt(pending ? null : now);
        deletionMapper.insertLedger(entry);
        if (pending) {
            purgeService.enqueue(objectKeys, entry.getId());
        }
        return entry.getId();
    }

    /** 账本主体摘要（小写十六进制 64 位）。 */
    public String digest(DeletionEventType type, long subjectId) {
        String subject = (type == DeletionEventType.REPORT_PURGE ? "report:" : "account:") + subjectId;
        return HexFormat.of().formatHex(hmac(digestKey, subject.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] hmac(SecretKeySpec key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            return mac.doFinal(data);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("删除账本摘要计算失败", ex);
        }
    }
}
