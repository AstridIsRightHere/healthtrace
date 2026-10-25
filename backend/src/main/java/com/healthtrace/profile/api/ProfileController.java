package com.healthtrace.profile.api;

import com.healthtrace.account.domain.AccountPrincipal;
import com.healthtrace.profile.domain.Relationship;
import com.healthtrace.profile.service.ProfileService;
import com.healthtrace.profile.service.ProfileView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 患者档案（FR-003，US-003）。所有端点需登录并携带 CSRF 令牌（写操作）。 */
@RestController
@RequestMapping("/api/v1/patient-profiles")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    /**
     * authorizationDeclared 必须为 true；声明时间与版本由服务端写入。
     * nickname 长度在服务端按去空白后的字符数校验，这里只做粗上限防止超大请求。
     */
    public record CreateProfileRequest(
            Relationship relationship,
            @Size(max = 1024) String nickname,
            Boolean authorizationDeclared) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProfileView create(@AuthenticationPrincipal AccountPrincipal principal,
                              @Valid @RequestBody CreateProfileRequest request) {
        return profileService.createProfile(principal.accountId(), request.relationship(), request.nickname(),
                Boolean.TRUE.equals(request.authorizationDeclared()));
    }

    @GetMapping("/current")
    public ProfileView current(@AuthenticationPrincipal AccountPrincipal principal) {
        return profileService.getCurrentProfile(principal.accountId());
    }

    @GetMapping("/{profileId}")
    public ProfileView get(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable long profileId) {
        return profileService.getProfile(principal.accountId(), profileId);
    }
}
