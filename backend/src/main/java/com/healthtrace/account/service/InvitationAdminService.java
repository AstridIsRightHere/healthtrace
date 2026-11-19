package com.healthtrace.account.service;

import com.healthtrace.account.api.admin.InvitationAdminController;
import com.healthtrace.account.domain.Invitation;
import com.healthtrace.account.mapper.InvitationMapper;
import com.healthtrace.rights.audit.AuditAction;
import com.healthtrace.rights.audit.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/**
 * 管理员：邀请码生成、禁用与查询（FR-018）。
 */
@Service
public class InvitationAdminService {

    private final InvitationMapper invitationMapper;
    private final AuditService auditService;
    private final Clock clock;
    private final SecureRandom secureRandom;

    public InvitationAdminService(InvitationMapper invitationMapper,
                                   AuditService auditService,
                                   Clock clock) {
        this.invitationMapper = invitationMapper;
        this.auditService = auditService;
        this.clock = clock;
        this.secureRandom = new SecureRandom();
    }

    /**
     * 生成邀请码（管理员操作）。
     * 明文 token 仅在创建时返回一次，数据库只存摘要。
     */
    @Transactional
    public InvitationAdminController.InvitationCreatedView createInvitation(long adminAccountId, Integer validDays) {
        LocalDateTime now = LocalDateTime.now(clock);
        
        // 生成 24 字节随机 token，Base64 编码为 32 字符
        byte[] tokenBytes = new byte[24];
        secureRandom.nextBytes(tokenBytes);
        String plainToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        
        // 计算 SHA-256 摘要
        String tokenDigest = sha256Hex(plainToken);
        
        LocalDateTime expiresAt = validDays != null ? now.plusDays(validDays) : null;
        
        Invitation invitation = new Invitation();
        invitation.setIssuerAccountId(adminAccountId);
        invitation.setTokenDigest(tokenDigest);
        invitation.setExpiresAt(expiresAt);
        
        invitationMapper.insert(invitation);
        
        auditService.success(adminAccountId, AuditAction.ADMIN_INVITATION_CREATE, null);
        
        return new InvitationAdminController.InvitationCreatedView(
                invitation.getId(),
                plainToken,
                expiresAt
        );
    }

    /**
     * 列出所有邀请码（管理员操作）。
     */
    public List<InvitationAdminController.InvitationListItemView> listAll() {
        return invitationMapper.findAll().stream()
                .map(inv -> new InvitationAdminController.InvitationListItemView(
                        inv.getId(),
                        inv.getCreatedAt(),
                        inv.getExpiresAt(),
                        inv.getUsedAt() != null,
                        inv.getUsedAt(),
                        inv.getUsedBy()
                ))
                .toList();
    }

    /**
     * 禁用邀请码（管理员操作）。
     * 将过期时间设为当前时刻。
     */
    @Transactional
    public void disableInvitation(long invitationId, long adminAccountId) {
        LocalDateTime now = LocalDateTime.now(clock);
        invitationMapper.setExpiresAt(invitationId, now);
        auditService.success(adminAccountId, AuditAction.ADMIN_INVITATION_DISABLE, null);
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
