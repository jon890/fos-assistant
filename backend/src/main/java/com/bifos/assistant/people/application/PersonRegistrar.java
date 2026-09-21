package com.bifos.assistant.people.application;

import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 사람 하나를 더하는 순서를 안다.
 *
 * <p>허용 목록에 넣고 Hermes profile 을 만드는 차례와, 중간에 실패했을 때 되돌리는 역순이 이 클래스
 * 하나에 있다. {@code hermes} 패키지는 부르는 방법만 알고 순서를 모른다.
 *
 * <p>여기까지가 관리자가 더할 때 일어나는 일이다. 그 사람의 {@code app_user} 와 에이전트는 그 사람이
 * 처음 로그인할 때 생긴다. 자기만 보는 에이전트는 주인이 있어야 하는데, 주인은 그 사람이 로그인하기
 * 전에는 존재하지 않기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class PersonRegistrar {

    private static final Logger log = LoggerFactory.getLogger(PersonRegistrar.class);

    private final AllowedPersonRepository people;
    private final AgentRepository agents;
    private final HermesProfileProvisioner profiles;

    /**
     * 허용 목록에 한 사람을 더하고 그 사람의 Hermes profile 을 만든다.
     *
     * <p>Hermes 를 부르기 전에 우리 표를 먼저 채운다. 이름이 겹치는지 우리 쪽에서 먼저 걸러야 Hermes
     * 에 헛일을 시키지 않는다.
     *
     * @throws ApiException 메일 주소나 profile 이름이 이미 쓰이고 있을 때. 이때 Hermes 를 부르지 않는다
     */
    public AllowedPerson register(String email, String displayName, String hermesProfile) {
        String normalized = AllowedPerson.normalizeEmail(email);
        if (people.existsByEmail(normalized)) {
            throw new ApiException(ErrorCode.PERSON_EMAIL_TAKEN, "this email is already on the list");
        }
        requireFreeProfileName(hermesProfile);

        AllowedPerson person = people.save(AllowedPerson.of(normalized, displayName, hermesProfile));
        try {
            profiles.provision(hermesProfile);
        } catch (RuntimeException failure) {
            throw rollback(person, failure);
        }
        return person;
    }

    /**
     * 그 profile 이름이 비어 있는지 두 곳에서 본다.
     *
     * <p>허용 목록과 에이전트 표가 각각 그 칸을 유일하게 갖는다. 한쪽만 보면 다른 쪽에서 쓰던 이름으로
     * profile 을 만들게 되고, 두 사람이 같은 profile 로 돌아 격리가 깨진다.
     */
    private void requireFreeProfileName(String hermesProfile) {
        if (people.existsByHermesProfile(hermesProfile) || agents.existsByHermesProfile(hermesProfile)) {
            throw new ApiException(
                    ErrorCode.PERSON_PROFILE_TAKEN, "this Hermes profile name is already used");
        }
    }

    /**
     * profile 을 만들지 못했으면 허용 목록 행도 되돌린다.
     *
     * <p>되돌리는 순서가 만드는 순서의 역순이다. Hermes 쪽은 {@link HermesProfileProvisioner} 가 이미
     * 거뒀고 여기서는 우리 표만 거둔다.
     *
     * <p>행을 지우지 못했으면 원래 오류를 그대로 올리고 지우기 실패는 로그로만 남긴다. 지우기 실패가
     * 원래 원인을 가리면 무엇 때문에 더하지 못했는지 알 수 없게 된다.
     */
    private RuntimeException rollback(AllowedPerson person, RuntimeException failure) {
        try {
            people.delete(person);
        } catch (RuntimeException rollbackFailure) {
            log.error(
                    "profile 을 만들지 못해 허용 목록 행을 거두려 했으나 그것도 실패했다. 그 주소로 다시 더할 수 없다 email={}",
                    person.email(),
                    rollbackFailure);
        }
        return failure;
    }
}
