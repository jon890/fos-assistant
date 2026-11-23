package com.bifos.assistant.testsupport;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.user.domain.AppUser;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 테스트 사용자의 기본 비서를 정한다.
 *
 * <p>기본 비서는 허용 목록이 그 사람에게 준 profile 을 가리키는 에이전트다({@code PrimaryAgents}). 테스트는 보통 허용 목록 없이 사용자와
 * 에이전트를 만들므로 기본 비서가 없고, 새 대화는 기본 비서로만 열린다(docs/features/assistant-hub/spec.md 의 REQ4). 그래서 대화를 여는
 * 테스트는 이것으로 그 사용자의 허용 목록 줄을 만들어 대화할 에이전트를 기본 비서로 둔다. 만든 줄은 {@link IntegrationTestIsolation} 이
 * 검사가 끝날 때 지워 다음 검사에 남지 않는다.
 */
public class TestPrimaryAgents {

    private final AllowedPersonRepository people;
    private final List<Long> saved = new CopyOnWriteArrayList<>();


    public TestPrimaryAgents(AllowedPersonRepository people) {
        this.people = people;
    }


    /** {@code agent} 를 {@code user} 의 기본 비서로 두고 그 에이전트를 돌려준다. 에이전트의 주인은 그 사용자여야 한다. */
    public Agent make(CurrentUser user, Agent agent) {
        return make(user.email(), user.displayName(), agent);
    }


    /** {@code agent} 를 {@code user} 의 기본 비서로 두고 그 에이전트를 돌려준다. 에이전트를 저장하는 식을 감싸 쓴다. */
    public Agent make(AppUser user, Agent agent) {
        return make(user.email(), user.displayName(), agent);
    }


    /**
     * 그 사람의 줄이 이미 같은 profile 이면 그대로 둔다. 다른 profile 이면 그 에이전트는 기본 비서가 되지 못하므로 바로 실패한다.
     * 돌려준 에이전트로 새 대화를 열면 {@code VALIDATION_FAILED} 가 나서 원인을 찾기 어렵기 때문이다.
     *
     * @throws IllegalStateException 그 사람에게 다른 기본 비서가 이미 있을 때
     */
    private Agent make(String email, String displayName, Agent agent) {
        Optional<AllowedPerson> existing = people.findByEmailAndEnabledTrue(AllowedPerson.normalizeEmail(email));
        if (existing.isEmpty()) {
            saved.add(people.save(AllowedPerson.of(email, displayName, agent.hermesProfile())).id());
        } else if (!existing.get().hermesProfile().equals(agent.hermesProfile())) {
            throw new IllegalStateException(
                email + " 에게는 이미 다른 기본 비서(" + existing.get().hermesProfile() + ")가 있어 " + agent.code()
                    + " 를 기본 비서로 둘 수 없다");
        }
        return agent;
    }


    /** 만든 허용 목록 줄을 지운다. 검사가 이미 지운 줄은 건너뛴다. */
    void reset() {
        people.deleteAll(people.findAllById(saved));
        saved.clear();
    }
}
