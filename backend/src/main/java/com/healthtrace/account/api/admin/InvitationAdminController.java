package com.healthtrace.account.api.admin;

import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.account.service.InvitationAdminService;
import com.healthtrace.common.web.ApiException;
import com.healthtrace.common.web.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理员：邀请码管理（FR-018）。
 * 生成、禁用与查看使用情况，不允许查看患者健康数据。
 */
@RestController
@RequestMapping("/api/v1/admin/invitations")
public class InvitationAdminController {

    private final InvitationAdminService invitationAdminService;

    public InvitationAdminController(InvitationAdminService invitationAdminService) {
        this.invitationAdminService = invitationAdminService;
    }

    /**
     * 生成邀请码（POST /api/v1/admin/invitations）。
     * 返回明文邀请码，仅在响应中显示一次。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationCreatedView createInvitation(@Valid @RequestBody CreateInvitationRequest request,
                                                   @AuthenticationPrincipal AccountPrincipal principal) {
        if (principal == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        return invitationAdminService.createInvitation(principal.accountId(), request.validDays());
    }

    /**
     * 列出所有邀请码（GET /api/v1/admin/invitations）。
     * 不返回原码，仅返回状态与使用情况。
     */
    @GetMapping
    public List<InvitationListItemView> listInvitations() {
        return invitationAdminService.listAll();
    }

    /**
     * 禁用邀请码（DELETE /api/v1/admin/invitations/{id}）。
     * 将过期时间设为当前时刻，使其不可用。
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disableInvitation(@PathVariable long id,
                                   @AuthenticationPrincipal AccountPrincipal principal) {
        if (principal == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        invitationAdminService.disableInvitation(id, principal.accountId());
    }

    public record CreateInvitationRequest(
            @NotNull Integer validDays
    ) {
    }

    public record InvitationCreatedView(
            long id,
            String plainToken,
            LocalDateTime expiresAt
    ) {
    }

    public record InvitationListItemView(
            long id,
            LocalDateTime createdAt,
            LocalDateTime expiresAt,
            boolean used,
            LocalDateTime usedAt,
            Long usedBy
    ) {
    }
}
