package com.bifos.assistant.people.application;

import com.bifos.assistant.chat.application.UserConversationActivity;
import com.bifos.assistant.people.application.dto.PersonAccess;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.auth.UserAccessRevoked;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 허용 목록에서 사용자를 켜고 끈다.
 *
 * <p>끄는 변경과 {@link UserAccessRevoked} 발행을 같은 트랜잭션에서 처리한다. 브라우저 정리는 커밋 뒤에
 * 사건을 받아 실행하며, 실패하면 다음 점검에서 다시 시도한다. 사용자 JWT는 매 요청 허용 목록을 확인해 거절한다(ADR-059).
 */
@Service
@RequiredArgsConstructor
public class PersonAccessService {

    private final AllowedPersonRepository people;
    private final AppUserRepository users;
    private final UserConversationActivity activity;
    private final ApplicationEventPublisher events;


    /**
     * 허용 목록 전체를 준다.
     *
     * <p>들어온 적이 있는지는 {@code app_user} 의 메일 주소를 한 번에 읽어 맞춘다. 사람마다 따로 묻지
     * 않는 것은 사용자 수만큼 질의가 늘어나기 때문이다.
     */
    @Transactional(readOnly = true)
    public List<PersonAccess> list() {
        List<AppUser> foundUsers = users.findAll();
        Map<Long, Instant> lastMessageAt = activity.lastMessageAt(foundUsers.stream().map(AppUser::id).toList());
        Map<String, List<AppUser>> usersByEmail = foundUsers.stream().collect(Collectors.groupingBy(user -> AllowedPerson.normalizeEmail(user.email())));
        return people.findAll().stream()
            .map(person -> {
                List<AppUser> matchingUsers = usersByEmail.get(person.email());
                Instant lastConversationAt = matchingUsers == null
                    ? null
                    : matchingUsers.stream()
                        .map(AppUser::id)
                        .map(lastMessageAt::get)
                        .filter(Objects::nonNull)
                        .max(Instant::compareTo)
                        .orElse(null);
                return new PersonAccess(person, matchingUsers != null, lastConversationAt);
            })
            .toList();
    }


    /**
     * 들어올 수 있는지를 올리거나 내린다. 내리면 그 사용자의 접근 해제 사건을 낸다.
     *
     * <p>app_user 는 정규화한 주소로 찾는다. 원문 주소를 그대로 비교하면 대소문자가 달라 사건을 보낼 사용자를
     * 빠뜨릴 수 있다.
     *
     * @throws ApiException 그 사람이 없으면 PERSON_NOT_FOUND
     */
    @Transactional
    public PersonAccess setEnabled(Long id, boolean enabled) {
        String email = people.findEmailById(id).orElseThrow(() -> new ApiException(ErrorCode.PERSON_NOT_FOUND, "no such person"));
        // 원고 저장 허가처럼 사용자 행 다음 허용 목록 행을 잠그는 쪽과 같은 순서로 잠근다. 사용자 번호 오름차순이 먼저다.
        users.findAllByNormalizedEmail(AllowedPerson.normalizeEmail(email)).stream()
            .map(AppUser::id)
            .sorted()
            .forEach(userId -> users.findByIdForUpdate(userId));
        AllowedPerson person = people.findByIdForUpdate(id)
            .orElseThrow(() -> new ApiException(ErrorCode.PERSON_NOT_FOUND, "no such person"));
        if (enabled) {
            person.enable();
        } else {
            person.disable();
        }
        AllowedPerson saved = people.save(person);
        List<AppUser> joined = users.findAllByNormalizedEmail(AllowedPerson.normalizeEmail(saved.email()));
        if (!enabled) {
            // 아직 로그인한 적이 없는 사용자는 app_user가 없어 정리 사건을 보낼 대상도 없다.
            joined.forEach(found -> events.publishEvent(new UserAccessRevoked(found.id())));
        }
        Instant lastConversationAt = joined.isEmpty()
            ? null
            : activity.lastMessageAt(joined.stream().map(AppUser::id).toList()).values().stream()
                .max(Instant::compareTo)
                .orElse(null);
        return new PersonAccess(saved, !joined.isEmpty(), lastConversationAt);
    }
}
