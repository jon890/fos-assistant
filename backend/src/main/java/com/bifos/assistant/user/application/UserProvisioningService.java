package com.bifos.assistant.user.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.people.application.PeopleProperties;
import com.bifos.assistant.people.application.SignInPolicy;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 부르는 사람을 저장된 사용자로 바꾼다.
 *
 * <p>웹 계층은 허용 목록에 있는 주소에만 토큰을 만들어 준다. 그래서 처음 들어온 사람이 그룹을 열고 그
 * 그룹의 관리자가 된다.
 *
 * <p>사용자를 새로 만드는 그 순간에 그 사람의 에이전트도 함께 만든다. 자기만 보는 에이전트는 주인이
 * 있어야 하는데, 주인은 그 사람이 로그인하기 전에는 존재하지 않기 때문이다. 순서와 어긋나는 지점은
 * {@code docs/backend/people.md} 의 「사람을 더할 때」가 갖는다.
 */
@Service
@RequiredArgsConstructor
public class UserProvisioningService {

    /** 지금은 그룹이 하나뿐이다. 그룹을 여럿 두려면 이 값을 조회로 바꾼다. */
    public static final long DEFAULT_GROUP_ID = 1L;

    private final AppUserRepository users;
    private final SignInPolicy signInPolicy;
    private final AgentRepository agents;
    private final HermesProperties hermesProperties;
    private final PeopleProperties peopleProperties;
    private final Clock clock;

    /**
     * 그 메일 주소의 사용자를 찾고, 없으면 만든다.
     *
     * <p>{@code ControlPlaneJwtFilter} 가 {@code AllowedUserResolver} 를 거쳐 매 요청 부른다. 그래서
     * **에이전트를 만들려고 시도하는 것은 사용자를 새로 저장하는 그 순간뿐이다.**
     *
     * <p>허용 목록에서 그 사람을 찾지 못하면({@code signInPolicy.admit} 가 비면) 사용자만 만들고
     * 에이전트는 만들지 않는다. 그런 사람과, 첫 로그인이 모델을 읽던 때에 에이전트 없이 들어온 사람은
     * 관리자가 기존 에이전트 등록 화면에서 만든다. 그때는 그 사람의 {@code app_user} 가 이미 있어
     * 주인을 지정할 수 있다.
     */
    @Transactional
    public AppUser resolve(String email, String displayName) {
        return users.findByEmail(email).orElseGet(() -> createUser(email, displayName));
    }

    private AppUser createUser(String email, String displayName) {
        AppUser created = users.save(AppUser.of(email, displayName, DEFAULT_GROUP_ID, firstUserRole(), clock.instant()));
        signInPolicy.admit(email).ifPresent(person -> createFirstAgent(created, person));
        return created;
    }

    /**
     * 그 사람의 profile 을 가리키는 에이전트를 하나 만든다.
     *
     * <p>Hermes 를 부르지 않는다. 에이전트는 모델을 갖지 않고, 대화가 모델을 고르지 않으면 그 profile 의
     * 기본값으로 돈다(ADR-030).
     */
    private void createFirstAgent(AppUser owner, AllowedPerson person) {
        agents.save(Agent.of(
                person.hermesProfile(),
                person.displayName(),
                person.hermesProfile(),
                hermesProperties.profileBaseUrl(person.hermesProfile()),
                peopleProperties.defaultCostMode(),
                peopleProperties.defaultCredentialScope(),
                AgentVisibility.PRIVATE,
                owner.id(),
                clock.instant()));
    }

    private UserRole firstUserRole() {
        return users.existsByGroupId(DEFAULT_GROUP_ID) ? UserRole.MEMBER : UserRole.ADMIN;
    }
}
