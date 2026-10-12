package com.bifos.assistant.people.application;

import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.auth.UserAccessPolicy;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 허용 목록에 켜져 있는 사용자인지 로그인 판정({@link SignInPolicy})으로 답한다.
 *
 * <p>{@code memory} 가 {@code people} 을 쓰지 않게 하려고 {@code shared.auth} 의 인터페이스로 나눴다(ADR-056).
 */
@Component
@RequiredArgsConstructor
public class AllowedUserAccessPolicy implements UserAccessPolicy {

    private final AppUserRepository users;
    private final SignInPolicy signInPolicy;
    private final AllowedPersonRepository people;

    @Override
    @Transactional(readOnly = true)
    public boolean allowed(Long userId) {
        return users.findById(userId)
                .map(AppUser::email)
                .flatMap(signInPolicy::admit)
                .isPresent();
    }


    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean allowedForUpdate(Long userId) {
        return users.findById(userId)
                .map(AppUser::email)
                .filter(email -> !email.isBlank())
                .flatMap(email -> people.findByEmailForUpdate(AllowedPerson.normalizeEmail(email)))
                .map(AllowedPerson::enabled)
                .orElse(false);
    }
}
