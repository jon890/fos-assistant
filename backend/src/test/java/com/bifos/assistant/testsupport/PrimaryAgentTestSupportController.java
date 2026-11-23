package com.bifos.assistant.testsupport;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.connector.connection.application.ConnectorBindingService;
import com.bifos.assistant.connector.connection.application.dto.AgentConnectionView;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import jakarta.persistence.EntityManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * e2e 와 브라우저 검사에서 부르는 사람의 기본 비서를 정한다.
 *
 * <p>새 대화는 기본 비서로만 열리고(docs/features/assistant-hub/spec.md 의 REQ4), 기본 비서는 허용 목록이 그 사람에게 준
 * profile 을 가리키는 에이전트다. 검사 사용자는 허용 목록 없이 토큰으로 들어오므로 기본 비서가 없다. 운영 경로로 사람을 더하면
 * Hermes profile 까지 새로 만들므로, 운영 코드에 문을 더하지 않고 여기서 허용 목록 줄만 바로 쓴다.
 *
 * <p>비서가 아닌 대화는 읽기 전용이라(REQ5) 다른 에이전트의 실행을 보는 검사는 그 에이전트를 잠시 기본 비서로 둔다
 * ({@link #useTemporarily}). 그룹 에이전트면 주인도 잠시 넘긴다. 기본 비서는 주인이 그 사람인 에이전트뿐이기 때문이다.
 * 검사가 끝나면 {@link #restore} 가 허용 목록 줄과 주인을 처음 값으로 되돌린다.
 */
@RestController
@RequestMapping("/api/v1/test-support/primary-agent")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "assistant.test-support.enabled", havingValue = "true")
public class PrimaryAgentTestSupportController {

    private final CurrentUserProvider currentUser;
    private final AgentService agents;
    private final EntityManager entityManager;
    /** 바인딩 서비스는 이 컨트롤러보다 늦게 만들어질 수 있어 부를 때 꺼낸다. */
    private final ObjectProvider<ConnectorBindingService> bindings;
    /** 잠시 바꾼 허용 목록 줄의 처음 profile 이다. 줄이 없었으면 빈 값이다. 메일 주소로 찾는다 */
    private final Map<String, Optional<String>> originalProfiles = new ConcurrentHashMap<>();
    /** 잠시 넘긴 에이전트의 처음 주인이다. 주인이 없었으면 빈 값이다 */
    private final Map<Long, Optional<Long>> originalOwners = new ConcurrentHashMap<>();


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
        writeProfile(AllowedPerson.normalizeEmail(user.email()), user.displayName(), agent.hermesProfile());
    }


    /** 부르는 사람이 쓸 수 있는 그 에이전트를 잠시 기본 비서로 둔다. {@link #restore} 가 되돌린다. */
    @PutMapping("/{agentCode}/temporary")
    @Transactional
    public void useTemporarilyByCode(@PathVariable String agentCode) {
        CurrentUser user = currentUser.require();
        useTemporarily(user, agents.requireStartable(user, agentCode));
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


    /**
     * 그 에이전트를 부르는 사람의 기본 비서로 잠시 둔다. 주인이 다르면 주인도 잠시 넘긴다. 처음 값은 한 번만 적어 두고
     * {@link #restore} 가 되돌린다.
     */
    @Transactional
    public void useTemporarily(CurrentUser user, Agent agent) {
        String email = AllowedPerson.normalizeEmail(user.email());
        if (!user.id().equals(agent.ownerUserId())) {
            originalOwners.putIfAbsent(agent.id(), Optional.ofNullable(agent.ownerUserId()));
            entityManager.createQuery("update Agent a set a.ownerUserId = :owner where a.id = :id")
                .setParameter("owner", user.id())
                .setParameter("id", agent.id())
                .executeUpdate();
        }
        List<String> current = entityManager
            .createQuery("select p.hermesProfile from AllowedPerson p where p.email = :email", String.class)
            .setParameter("email", email)
            .getResultList();
        originalProfiles.putIfAbsent(email, current.stream().findFirst());
        writeProfile(email, user.displayName(), agent.hermesProfile());
    }


    /** {@link #useTemporarily} 로 바꾼 허용 목록 줄과 에이전트 주인을 모두 처음 값으로 되돌린다. 바꾼 것이 없으면 그대로다. */
    @PostMapping("/restore")
    @Transactional
    public void restore() {
        originalOwners.forEach((agentId, owner) -> entityManager
            .createQuery("update Agent a set a.ownerUserId = :owner where a.id = :id")
            .setParameter("owner", owner.orElse(null))
            .setParameter("id", agentId)
            .executeUpdate());
        originalOwners.clear();
        originalProfiles.forEach((email, profile) -> {
            if (profile.isPresent()) {
                writeProfile(email, email, profile.get());
            } else {
                entityManager.createQuery("delete from AllowedPerson p where p.email = :email")
                    .setParameter("email", email)
                    .executeUpdate();
            }
        });
        originalProfiles.clear();
    }


    /**
     * 기본 비서에 연결을 붙인다. 화면의 붙이기는 기본 비서를 거절하므로(docs/features/assistant-hub/spec.md 의 REQ9) 배포 전부터 비서에
     * 붙은 연결을 흉내 내는 시나리오가 쓴다. 검사는 화면의 붙이기와 같고 기본 비서인지만 보지 않는다.
     */
    @PutMapping("/legacy-connections/{agentCode}/{connectorId}")
    public AgentConnectionView bindLegacy(@PathVariable String agentCode, @PathVariable String connectorId) {
        return bindings.getObject().bind(currentUser.require(), agentCode, connectorId);
    }


    /**
     * 잠시 바꾸기 전의 값을 돌려준다. 이 값은 메모리에만 있어 Control Plane 을 다시 띄우면 잃으므로, e2e 가 재기동 전에 읽어
     * 두었다가 {@link #rememberOriginals} 로 다시 넣는다. 바꾼 값 자체는 DB 에 있어 재기동해도 남는다. 값이 없던 칸은 null 이다.
     */
    @GetMapping("/originals")
    public Originals originals() {
        Map<String, String> profiles = new HashMap<>();
        originalProfiles.forEach((email, profile) -> profiles.put(email, profile.orElse(null)));
        Map<Long, Long> owners = new HashMap<>();
        originalOwners.forEach((agentId, owner) -> owners.put(agentId, owner.orElse(null)));
        return new Originals(profiles, owners);
    }


    /** {@link #originals} 로 읽어 둔 값을 다시 넣는다. 이미 적힌 값은 그대로 둔다. */
    @PutMapping("/originals")
    public void rememberOriginals(@RequestBody Originals originals) {
        originals.profiles().forEach((email, profile) -> originalProfiles.putIfAbsent(email, Optional.ofNullable(profile)));
        originals.owners().forEach((agentId, owner) -> originalOwners.putIfAbsent(agentId, Optional.ofNullable(owner)));
    }


    /** 잠시 바꾸기 전의 허용 목록 profile(메일 주소별)과 에이전트 주인(에이전트 번호별)이다. */
    public record Originals(Map<String, String> profiles, Map<Long, Long> owners) {
    }


    /** 허용 목록 줄의 profile 을 바꾸고, 줄이 없으면 만든다. */
    private void writeProfile(String email, String displayName, String profile) {
        int changed = entityManager
            .createQuery("update AllowedPerson p set p.hermesProfile = :profile where p.email = :email")
            .setParameter("profile", profile)
            .setParameter("email", email)
            .executeUpdate();
        if (changed == 0) {
            entityManager.persist(AllowedPerson.of(email, displayName, profile));
        }
    }
}
