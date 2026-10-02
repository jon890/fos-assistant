package com.bifos.assistant.user.application;

import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.TokenUserResolver;
import com.bifos.assistant.user.domain.AppUser;
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
public class AllowedUserResolver implements TokenUserResolver {

    private final SignInRevocation signInRevocation;
    private final UserProvisioningService provisioning;

    /**
     * 꺼진 주소면 비어 있는 값을, 아니면 {@link UserProvisioningService#resolve} 의 사용자를 돌려준다.
     *
     * <p>허용 목록의 줄이 꺼져 있을 때만 막고, 줄이 없는 주소는 막지 않는다. 판정을 먼저 하므로 꺼진
     * 주소로는 {@code app_user} 를 찾지도 만들지도 않는다. 두 조회는 같은 트랜잭션에서 돈다.
     */
    @Transactional
    public Optional<AppUser> resolveAllowed(String email, String displayName) {
        if (signInRevocation.revoked(email)) {
            return Optional.empty();
        }
        return Optional.of(provisioning.resolve(email, displayName));
    }

    /**
     * {@link #resolveAllowed} 의 사용자를 인증 주체로 바꿔 돌려준다. 꺼진 주소면 비어 있는 값이다.
     *
     * <p>같은 클래스의 메서드를 부르면 프록시를 거치지 않으므로 여기서도 트랜잭션을 연다. 판정과 조회가
     * 한 트랜잭션에서 돈다.
     */
    @Override
    @Transactional
    public Optional<CurrentUser> resolveCurrentUser(String email, String displayName) {
        return resolveAllowed(email, displayName)
                .map(user -> new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role()));
    }
}
