package com.bifos.assistant.user.application;

import com.bifos.assistant.people.application.SignInPolicy;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserProvisioningService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 웹 토큰이 가리키는 주소를, 꺼지지 않았을 때만 저장된 사용자로 바꾼다.
 *
 * <p>{@code ControlPlaneJwtFilter} 가 매 요청 부른다. 허용 목록은 로그인할 때만 확인하므로, 관리자가 끈
 * 사용자를 여기서 요청마다 걸러 낸다(ADR-059).
 */
@Service
@RequiredArgsConstructor
public class AllowedUserResolver {

    private final SignInPolicy signInPolicy;
    private final UserProvisioningService provisioning;

    /**
     * 꺼진 주소면 비어 있는 값을, 아니면 {@link UserProvisioningService#resolve} 의 사용자를 돌려준다.
     *
     * <p>허용 목록의 줄이 꺼져 있을 때만 막고, 줄이 없는 주소는 막지 않는다. 판정을 먼저 하므로 꺼진
     * 주소로는 {@code app_user} 를 찾지도 만들지도 않는다. 두 조회는 같은 트랜잭션에서 돈다.
     */
    @Transactional
    public Optional<AppUser> resolveAllowed(String email, String displayName) {
        if (signInPolicy.revoked(email)) {
            return Optional.empty();
        }
        return Optional.of(provisioning.resolve(email, displayName));
    }
}
