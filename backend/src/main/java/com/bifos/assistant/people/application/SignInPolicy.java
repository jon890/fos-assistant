package com.bifos.assistant.people.application;

import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import com.bifos.assistant.user.application.port.SignInRevocation;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 메일 주소 하나의 로그인 허용 여부를 판정하고 로그인 완료 시각을 기록한다.
 *
 * <p>사용자는 만들지 않는다. 로그인 판정은 아직 아무 사용자도 없는 시점에 돌고, 완료 기록도 허용 목록만
 * 조건부로 갱신한다.
 */
@Service
@RequiredArgsConstructor
public class SignInPolicy implements SignInRevocation {

    private final AllowedPersonRepository people;
    private final AppUserRepository users;
    private final Clock clock;


    /** 들어와도 되면 그 사람을, 아니면 비어 있는 값을 돌려준다. */
    @Transactional(readOnly = true)
    public Optional<AllowedPerson> admit(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        String normalized = AllowedPerson.normalizeEmail(email);
        if (evaluation(normalized)) {
            return Optional.empty();
        }
        return people.findByEmailAndEnabledTrue(normalized);
    }


    /**
     * 그 주소의 줄이 꺼져 있는가.
     *
     * <p>평가 전용 사용자는 여기서 막지 않는다. 예약 작업 발화가 이 판정으로 주인을 확인해, 막으면 배포 뒤 확인의 예약 작업 발화가 늘
     * 건너뛰어진다(docs/features/agent-quality/design.md 의 「숨기는 곳」). 로그인은 {@link #admit} 과
     * {@link #recordCompletion} 이 막는다.
     *
     * <p>줄이 없으면 거짓이다(ADR-059).
     */
    @Override
    @Transactional(readOnly = true)
    public boolean revoked(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        return people.existsByEmailAndEnabledFalse(AllowedPerson.normalizeEmail(email));
    }


    /** 로그인 완료 시각을 기록한다. 허용하지 않은 주소는 거절한다. */
    @Transactional
    public boolean recordCompletion(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        String normalized = AllowedPerson.normalizeEmail(email);
        if (evaluation(normalized)) {
            return false;
        }
        if (people.updateLastLoginAtIfNewer(normalized, clock.instant()) > 0) {
            return true;
        }
        return people.findByEmailAndEnabledTrue(normalized).isPresent();
    }


    /** 평가 전용 사용자의 주소인가. 평가 전용 사용자는 로그인하지 않는다. */
    private boolean evaluation(String normalizedEmail) {
        return users.findAllByNormalizedEmail(normalizedEmail).stream().anyMatch(AppUser::evaluation);
    }
}
