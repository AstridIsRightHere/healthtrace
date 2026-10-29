package com.healthtrace.rights.audit;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 写入审计事件（docs/19 AUDIT_EVENT）。
 * <ul>
 *   <li>加入调用方事务：业务回滚时“成功”审计一并回滚，库中不会出现未发生的成功操作。</li>
 *   <li>resource_token 只放最小关联标识（如 report:42），不写病历正文、邮箱或令牌。</li>
 * </ul>
 */
@Service
public class AuditService {

    private final AuditMapper auditMapper;
    private final Clock clock;

    public AuditService(AuditMapper auditMapper, Clock clock) {
        this.auditMapper = auditMapper;
        this.clock = clock;
    }

    public void success(Long actorAccountId, AuditAction action, String resourceToken) {
        record(actorAccountId, action, resourceToken, AuditOutcome.SUCCESS);
    }

    public void record(Long actorAccountId, AuditAction action, String resourceToken, AuditOutcome outcome) {
        AuditEvent event = new AuditEvent();
        event.setActorAccountId(actorAccountId);
        event.setAction(action);
        event.setResourceToken(resourceToken);
        event.setOutcome(outcome);
        event.setOccurredAt(LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
        auditMapper.insert(event);
    }

    public static String token(String type, long id) {
        return type + ":" + id;
    }
}
