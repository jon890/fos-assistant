package com.bifos.assistant.testsupport;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * e2e 와 브라우저 검사에서 부르는 사람의 기본 비서를 정한다.
 *
 * <p>새 대화는 기본 비서로만 열리고(docs/features/assistant-hub/spec.md 의 REQ4), 기본 비서는 허용 목록이 그 사람에게 준
 * profile 을 가리키는 에이전트다. 검사 사용자는 허용 목록 없이 토큰으로 들어오므로 기본 비서가 없다. 운영 경로로 사람을 더하면
 * Hermes profile 까지 새로 만들므로, 운영 코드에 문을 더하지 않고 여기서 허용 목록 줄만 바로 쓴다.
 */
@RestController
@RequestMapping("/api/v1/test-support/primary-agent")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "assistant.test-support.enabled", havingValue = "true")
public class PrimaryAgentTestSupportController {

    private final CurrentUserProvider currentUser;
    private final AgentService agents;
    private final EntityManager entityManager;


    /**
     * 부르는 사람이 주인인 그 에이전트를 기본 비서로 둔다. 허용 목록 줄이 있으면 profile 만 바꾸고 없으면 만든다.
     *
     * <p>남의 에이전트는 운영 경로와 같은 404 {@code AGENT_NOT_FOUND} 다.
     */
    @PutMapping("/{agentCode}")
    @Transactional
    public void use(@PathVariable String agentCode) {
        CurrentUser user = currentUser.require();
        Agent agent = agents.requireReadable(user, agentCode);
        if (!user.id().equals(agent.ownerUserId())) {
            throw new ApiException(ErrorCode.AGENT_NOT_FOUND, "no such agent");
        }
        String email = AllowedPerson.normalizeEmail(user.email());
        int changed = entityManager
            .createQuery("update AllowedPerson p set p.hermesProfile = :profile where p.email = :email")
            .setParameter("profile", agent.hermesProfile())
            .setParameter("email", email)
            .executeUpdate();
        if (changed == 0) {
            entityManager.persist(AllowedPerson.of(email, user.displayName(), agent.hermesProfile()));
        }
    }


    /** 부르는 사람의 허용 목록 줄을 지워 기본 비서를 없앤다. 검사가 만든 에이전트를 지우기 전에 부른다. 기본 비서는 지울 수 없다. */
    @DeleteMapping
    @Transactional
    public void clear() {
        CurrentUser user = currentUser.require();
        entityManager
            .createQuery("delete from AllowedPerson p where p.email = :email")
            .setParameter("email", AllowedPerson.normalizeEmail(user.email()))
            .executeUpdate();
    }
}
