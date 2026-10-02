package com.bifos.assistant.people.application;

import com.bifos.assistant.people.application.model.PersonAccess;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.auth.UserAccessRevoked;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 허용 목록에서 사람을 켜고 끈다.
 *
 * <p>끄는 저장과 {@link UserAccessRevoked} 를 받는 쪽의 폐기가 한 트랜잭션이다. 이벤트는 동기로 전달되므로
 * 폐기가 실패하면 끄기도 함께 되돌아간다(ADR-056). 컨트롤러에서 저장과 이벤트를 따로 두면 저장이 먼저
 * 커밋돼 폐기가 실패해도 꺼진 채 토큰이 남는다.
 */
@Service
@RequiredArgsConstructor
public class PersonAccessService {

    private final AllowedPersonRepository people;
    private final AppUserRepository users;
    private final ApplicationEventPublisher events;

    /**
     * 허용 목록 전체를 준다.
     *
     * <p>들어온 적이 있는지는 {@code app_user} 의 메일 주소를 한 번에 읽어 맞춘다. 사람마다 따로 묻지
     * 않는 것은 사용자 수만큼 질의가 늘어나기 때문이다.
     */
    @Transactional(readOnly = true)
    public List<PersonAccess> list() {
        Set<String> joined = users.findAll().stream()
                .map(AppUser::email)
                .map(AllowedPerson::normalizeEmail)
                .collect(Collectors.toSet());
        return people.findAll().stream()
                .map(person -> new PersonAccess(person, joined.contains(person.email())))
                .toList();
    }

    /**
     * 들어올 수 있는지를 올리거나 내린다. 내리면 그 사용자가 가진 것을 거두는 사건을 낸다.
     *
     * <p>app_user 는 정규화한 주소로 찾는다. 원문 주소를 그대로 비교하면 대소문자가 달라 찾지 못하고, 토큰이
     * 폐기되지 않은 채 남는다.
     *
     * @throws ApiException 그 사람이 없으면 PERSON_NOT_FOUND
     */
    @Transactional
    public PersonAccess setEnabled(Long id, boolean enabled) {
        AllowedPerson person =
                people.findById(id).orElseThrow(() -> new ApiException(ErrorCode.PERSON_NOT_FOUND, "no such person"));
        if (enabled) {
            person.enable();
        } else {
            person.disable();
        }
        AllowedPerson saved = people.save(person);
        List<AppUser> joined = users.findAllByNormalizedEmail(AllowedPerson.normalizeEmail(saved.email()));
        if (!enabled) {
            // 아직 로그인한 적이 없는 사람은 app_user 가 없어 거둘 토큰도 없다
            joined.forEach(found -> events.publishEvent(new UserAccessRevoked(found.id())));
        }
        return new PersonAccess(saved, !joined.isEmpty());
    }
}
