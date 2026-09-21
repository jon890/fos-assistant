package com.bifos.assistant.user.domain;

import com.bifos.assistant.agent.application.AgentModelSelector;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.ModelOption;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.HermesModelClient;
import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.hermes.dto.HermesModelOptions;
import com.bifos.assistant.people.application.PeopleProperties;
import com.bifos.assistant.people.application.SignInPolicy;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.user.infra.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 부르는 사람을 저장된 사용자로 바꾼다.
 *
 * <p>웹 계층은 허용 목록에 있는 주소에만 토큰을 만들어 준다. 그래서 처음 들어온 사람이 가족을 열고 그
 * 가족의 관리자가 된다.
 *
 * <p>사용자를 새로 만드는 그 순간에 그 사람의 에이전트도 함께 만든다. 자기만 보는 에이전트는 주인이
 * 있어야 하는데, 주인은 그 사람이 로그인하기 전에는 존재하지 않기 때문이다. 순서와 어긋나는 지점은
 * {@code docs/flow.md} 의 「사람을 더할 때」가 갖는다.
 */
@Service
@RequiredArgsConstructor
public class UserProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(UserProvisioningService.class);

    /** Single household for the MVP. Multi-family support would replace this with a lookup. */
    public static final long DEFAULT_FAMILY_ID = 1L;

    private final AppUserRepository users;
    private final SignInPolicy signInPolicy;
    private final AgentRepository agents;
    private final AgentModelSelector models;
    private final HermesModelClient hermesModels;
    private final HermesProperties hermesProperties;
    private final PeopleProperties peopleProperties;

    /**
     * 그 메일 주소의 사용자를 찾고, 없으면 만든다.
     *
     * <p>이 경로는 {@code ControlPlaneJwtFilter} 가 매 요청 부른다. 그래서 **에이전트를 만들려고
     * 시도하는 것은 사용자를 새로 저장하는 그 순간뿐이다.** 「에이전트가 없으면 다시 만든다」로 하면
     * 그 사람의 모든 요청이 Hermes 호출 하나를 트랜잭션 안에서 끌고 다니고, Hermes 가 답하지 않으면
     * 화면 전체가 요청마다 그 한도만큼 늘어진다.
     *
     * <p>에이전트 없이 지나간 사람은 관리자가 에이전트 등록 화면에서 만든다. 그때는 그 사람의
     * {@code app_user} 가 이미 있어 주인을 지정할 수 있다.
     */
    @Transactional
    public AppUser resolve(String email, String displayName) {
        return users.findByEmail(email).orElseGet(() -> createUser(email, displayName));
    }

    private AppUser createUser(String email, String displayName) {
        AppUser created =
                users.save(AppUser.of(email, displayName, DEFAULT_FAMILY_ID, firstUserRole()));
        signInPolicy.admit(email).ifPresent(person -> createFirstAgent(created, person));
        return created;
    }

    /**
     * 그 사람의 profile 을 가리키는 에이전트를 하나 만든다.
     *
     * <p>모델과 provider 를 Hermes 에서 읽어 채운다. 둘 중 하나라도 읽지 못하면 **에이전트를 만들지
     * 않고 로그인은 그대로 통과시킨다.** 기본값으로 메우면 틀린 provider 로 만들어진 에이전트가
     * 실행할 때마다 실패하고, 그 원인이 화면에 드러나지 않는다.
     */
    private void createFirstAgent(AppUser owner, AllowedPerson person) {
        String apiBaseUrl = hermesProperties.profileBaseUrl(person.hermesProfile());
        HermesModelOptions options = hermesModels.readOptions(apiBaseUrl, person.hermesProfile());
        ModelOption first = options == null
                ? null
                : new ModelOption(options.provider(), options.model());
        if (first == null || !first.complete()) {
            log.warn(
                    "첫 로그인에 에이전트를 만들지 않았다. Hermes 가 provider 와 모델을 주지 않았다 profile={}",
                    person.hermesProfile());
            return;
        }
        Agent agent = Agent.of(
                person.hermesProfile(),
                person.displayName(),
                person.hermesProfile(),
                apiBaseUrl,
                first.provider(),
                first.model(),
                peopleProperties.defaultCostMode(),
                peopleProperties.defaultCredentialScope(),
                AgentVisibility.PRIVATE,
                owner.id());
        // 방금 Hermes 에서 읽은 값이다. 읽은 시각을 비워 두면 관리 화면이 한 번도 읽지 않은 것으로 보인다.
        agent.syncModel(first.model());
        Agent saved = agents.save(agent);
        // 목록이 비어 있으면 첫 실행이 쓸 모델을 찾지 못해 NO_MODEL_AVAILABLE 로 끝난다.
        models.seedFirst(saved, first);
    }

    private UserRole firstUserRole() {
        return users.existsByFamilyId(DEFAULT_FAMILY_ID) ? UserRole.MEMBER : UserRole.ADMIN;
    }
}
