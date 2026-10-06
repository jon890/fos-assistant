package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentToolPolicy;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사용자가 에이전트를 만들고, 공개 범위를 바꾸고, 지우는 순서를 안다.
 *
 * <p>만들기는 한 요청 안에서 profile 까지 끝내고, 중간에 실패하면 만든 profile 을 거둔다. 지우기는
 * Control Plane 이 만든 profile 만 profile 까지 거둔다. 근거는 ADR-033 이다.
 *
 * <p>그룹 공개 검사도 여기 둔다. 관리자 경로와 사용자 경로가 같은 코드를 불러야 한쪽만 느슨해지지 않는다.
 *
 * <p>공개 범위 변경과 지우기는 에이전트 번호를 트랜잭션 밖에서 읽고, 트랜잭션은 잠금 읽기로 시작한다. MySQL 의 REPEATABLE READ
 * 는 트랜잭션의 첫 일반 읽기에서 읽는 시점을 정한다. 잠금을 기다리기 전에 일반 읽기를 하면 기다린 뒤의 바인딩 조회가 붙이기가
 * 그 사이 커밋한 바인딩을 보지 못한다(ADR-083).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AgentLifecycleService {

    /** {@code agent.name} 칸의 길이와 같다. */
    private static final int MAX_NAME_CHARS = 100;

    /** 무작위로 뽑는 글자 수. 소문자 영숫자 36자에서 10자라 겹칠 일이 거의 없다. */
    private static final int RANDOM_CHARS = 10;

    private static final String RANDOM_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    /** 에이전트 번호의 머리. profile 이름 규칙에 맞게 영문 소문자로 시작한다. */
    private static final String CODE_PREFIX = "a";

    /** 사용자가 만든 profile 의 머리. 운영에서 손으로 만든 profile 과 이름으로도 구분된다. */
    private static final String PROFILE_PREFIX = "ua-";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AgentRepository agents;
    private final AgentService agentService;
    private final AppUserRepository users;
    private final ReservedProfileNames reservedProfileNames;
    private final ProfileProvisioning provisioner;
    private final HermesToolsetClient hermesToolsets;
    private final HermesProperties hermesProperties;
    private final PeopleProperties peopleProperties;
    private final AgentProperties properties;
    private final ProfileSkillFiles skillFiles;
    private final AgentConnectorBindings connectorBindings;
    private final AgentConnectorDetacher connectorDetacher;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 요청자의 에이전트를 만든다. 주인은 요청자다. {@code ADMIN} 이 만들어도 자기 것이 된다.
     *
     * <p>{@code ADMIN} 이 아니면 요청자의 {@code app_user} 행을 잠근 채 수를 세고 끝까지 만든다. 같은 사람이
     * 두 번 눌러도 둘째 요청은 첫째가 커밋한 뒤에 세므로 상한을 넘지 않는다. Hermes 를 부르는 몇 초 동안
     * 잠금을 쥐지만 그 사람의 행이라 다른 사용자는 기다리지 않는다.
     *
     * <p>profile 을 만든 뒤에 그 profile 의 도구 목록을 읽어 plugin 틀이 안전한 기본 도구를 붙였는지 본다.
     * 셸이나 파일 등급이 켜져 있으면 틀이 적용되지 않은 것으로 보고 거둔다. 그래서 그룹 공개로 만들어도
     * 따로 검사하지 않는다. MCP 등록은 이 목록으로 볼 수 없어 profile 만들기가 성공한 것으로 믿는다.
     *
     * <p>두 가지를 알고 받아들인다.
     *
     * <ul>
     *   <li>만들기 한 건이 DB 커넥션을 둘 쓴다. 잠금을 쥔 이 트랜잭션과, profile 을 만들며 토큰을 발급하거나
     *       폐기하는 {@code REQUIRES_NEW} 트랜잭션이다. 서로 다른 사용자의 동시 만들기가 커넥션 풀 크기 이상이면
     *       모두 둘째 커넥션을 기다리며 막힐 수 있다. 가족 규모에서는 그만큼 동시에 만들 일이 없다.
     *   <li>{@code saveAndFlush} 가 성공한 뒤 커밋 단계에서 실패하면 행은 되돌려지고 profile 은 남는다.
     *       커밋 실패는 드물어 여기서 거두지 않는다. 남은 profile 은 운영에서 지운다.
     * </ul>
     *
     * @param visibility 비어 있으면 {@code PRIVATE}
     */
    @Transactional
    public Agent create(CurrentUser user, String name, AgentVisibility visibility) {
        if (!user.isAdmin()) {
            requireBelowLimit(user);
        }
        return provisionAgent(user, name, visibility);
    }

    private Agent provisionAgent(CurrentUser user, String name, AgentVisibility visibility) {
        String agentName = requireName(name);
        AgentVisibility effectiveVisibility = visibility == null ? AgentVisibility.PRIVATE : visibility;

        String code = CODE_PREFIX + randomChars();
        String profileName = PROFILE_PREFIX + randomChars();
        if (isTaken(code, profileName)) {
            code = CODE_PREFIX + randomChars();
            profileName = PROFILE_PREFIX + randomChars();
            if (isTaken(code, profileName)) {
                throw new ApiException(
                        ErrorCode.HERMES_PROVISION_FAILED, "could not pick a free agent code and profile name");
            }
        }
        String apiBaseUrl = hermesProperties.profileBaseUrl(profileName);

        provisioner.provision(profileName);
        try {
            if (AgentToolPolicy.hasPrivateOnlyToolset(hermesToolsets.readEnabled(apiBaseUrl, profileName))) {
                throw new ApiException(
                        ErrorCode.HERMES_PROVISION_FAILED, "the new profile did not get the safe default toolsets");
            }
            Agent agent = Agent.of(
                    code,
                    agentName,
                    profileName,
                    apiBaseUrl,
                    peopleProperties.defaultCostMode(),
                    CredentialScope.SHARED_HOUSEHOLD,
                    effectiveVisibility,
                    user.id(),
                    clock.instant());
            agent.markManagedProfile();
            // 제약 위반이 커밋 때가 아니라 여기서 드러나야 profile 을 거둘 수 있다.
            return agents.saveAndFlush(agent);
        } catch (RuntimeException failure) {
            withdraw(profileName);
            throw failure;
        }
    }

    /**
     * 공개 범위를 바꾼다. 주인이 있으면 그대로 둔다(ADR-033).
     *
     * <p>주인이 비어 있는 에이전트를 자기만 보는 것으로 바꾸면 요청자를 주인으로 정한다. 주인 없이 바꾸면
     * 아무도 읽지 못하는 에이전트가 된다. 주인이 없는 에이전트는 {@code ADMIN} 만 여기까지 오므로 그
     * {@code ADMIN} 이 주인이 된다. 관리자 경로가 주인 메일로 관리자 자신을 받던 것과 같은 결과다. 그룹으로
     * 두는 요청은 주인을 비운 채 둔다.
     *
     * <p>켜진 에이전트를 그룹으로 바꿀 때만 도구를 검사한다. 관리자 경로의 수정과 같은 기준이다.
     *
     * <p>연결이 붙은 에이전트는 그룹으로 바꾸지 못한다(ADR-083). 붙이기와 같은 에이전트 행을 기다려 잠그므로, 붙이기가 그 잠금을
     * 쥔 동안 온 요청은 붙이기가 커밋한 바인딩을 보고 거절된다.
     */
    public Agent changeVisibility(CurrentUser user, String code, AgentVisibility visibility) {
        if (visibility == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "a visibility is required");
        }
        Long agentId = agents.findIdByCode(code).orElseThrow(AgentLifecycleService::notFound);
        return transactions.execute(status -> changeVisibilityLocked(user, agentId, visibility));
    }

    private Agent changeVisibilityLocked(CurrentUser user, Long agentId, AgentVisibility visibility) {
        Agent agent = requireManageable(user, lockById(agentId));
        Long ownerId =
                visibility == AgentVisibility.PRIVATE && agent.ownerUserId() == null ? user.id() : agent.ownerUserId();
        // 바인딩은 에이전트 행을 잠근 읽기를 마친 뒤에 읽는다. 앞으로 옮기면 붙이기가 커밋한 바인딩을 보지 못해 그룹 공개와
        // 바인딩이 함께 남는다.
        if (visibility == AgentVisibility.GROUP && connectorBindings.hasBindings(agent.id())) {
            throw new ApiException(
                    ErrorCode.AGENT_CONNECTIONS_REQUIRE_PRIVATE, "an agent with connections must stay private");
        }
        // 꺼진 에이전트는 여기서 검사하지 않는다. 켤 때 관리자 경로의 수정이 같은 검사를 한다.
        if (agent.enabled() && visibility == AgentVisibility.GROUP) {
            requireGroupSafe(agent.apiBaseUrl(), agent.hermesProfile());
        }
        agent.changeAccess(agent.enabled(), visibility, ownerId);
        return agents.save(agent);
    }

    /**
     * 에이전트를 지운다. 행은 남기고 지운 시각을 적는다.
     *
     * <p>붙은 연결을 먼저 모두 뗀다(ADR-083). 사람이 만든 profile 은 거두지 않으므로 떼지 않으면 그 profile 에 커넥터 서버와
     * 값이 남는다. 그 뒤 Control Plane 이 만든 profile 이면 profile 을 거둔다. 떼거나 거두다 실패하면 지우지 않고 그 오류를
     * 올린다. 지운 것으로 적은 뒤에는 다시 거둘 길이 없기 때문이다. 운영에서 만든 profile 과 사용자의 기본 profile 은 남긴다.
     *
     * <p>profile 을 거둔 뒤 그 profile 의 스킬 디렉터리를 지운다. 이것이 실패해도 삭제는 성공으로 둔다.
     * profile 은 이미 지워져 되돌릴 수 없고, 남은 디렉터리는 아무것도 가리키지 않는다.
     *
     * <p>지우기는 주인과 {@code ADMIN} 이 한다. 옛 커넥터 에이전트도 지운다. 사용자가 새 방식으로 옮긴 뒤 그 에이전트를 지울
     * 길이 이것뿐이다.
     *
     * <p>붙이기와 같은 차례로 주인의 사용자 행 다음에 에이전트 행을 잠근다. 떼기가 거절하는 승인 줄을 승인이 같은 사용자 행을
     * 먼저 잠그고 실행으로 바꾸므로, 주인의 행을 잠가야 그 사이 실행으로 바뀌는 줄이 없다. 주인은 트랜잭션 밖에서 읽고 잠근 뒤
     * 다시 견준다.
     */
    public void delete(CurrentUser user, String code) {
        Agent found = agents.findByCode(code).orElseThrow(AgentLifecycleService::notFound);
        requireDeletable(user, found);
        Long ownerId = found.ownerUserId();
        transactions.executeWithoutResult(status -> deleteLocked(user, found.id(), ownerId));
    }

    private void deleteLocked(CurrentUser user, Long agentId, Long ownerId) {
        if (ownerId != null) {
            users.findByIdForUpdate(ownerId);
        }
        Agent agent = requireDeletable(user, lockById(agentId));
        if (!Objects.equals(agent.ownerUserId(), ownerId)) {
            // 주인을 읽은 뒤 바뀌었다. 잠근 사용자 행이 지금 주인의 것이 아니다.
            throw new ApiException(ErrorCode.AGENT_BUSY, "the agent owner changed while deleting");
        }
        connectorDetacher.detachAll(agent);
        if (agent.profileManaged()) {
            provisioner.deprovision(agent.hermesProfile());
            removeSkillDirectory(agent.hermesProfile());
        }
        agent.markDeleted(clock.instant());
        agents.save(agent);
    }

    private void removeSkillDirectory(String profileName) {
        try {
            skillFiles.deleteAll(profileName);
        } catch (RuntimeException failure) {
            log.warn("지운 에이전트의 스킬 디렉터리를 지우지 못했다. 쓰이지 않을 디렉터리가 남는다 profile={}", profileName, failure);
        }
    }

    /**
     * 그 profile 을 그룹에 공개해도 되는지 본다. 셸이나 파일 등급의 도구가 켜져 있으면 거절한다.
     *
     * <p>{@link Agent} 가 아니라 주소와 profile 이름을 받는다. 관리자 경로는 행을 만들기 전의 요청 값으로,
     * 또는 바꿀 새 주소로 검사하기 때문이다.
     */
    public void requireGroupSafe(String apiBaseUrl, String profileName) {
        if (AgentToolPolicy.hasPrivateOnlyToolset(hermesToolsets.readEnabled(apiBaseUrl, profileName))) {
            throw new ApiException(
                    ErrorCode.AGENT_TOOLS_REQUIRE_PRIVATE, "shell and file toolsets require a private agent");
        }
    }

    /**
     * 그 profile 의 주인을 바꿔도 되는지 본다. 셸이나 파일 등급의 도구가 켜져 있으면 거절한다(ADR-086).
     *
     * <p>도구를 켤 때 profile 의 실행 공간이 그때 주인의 디렉터리로 정해지고, 오래 가는 컨테이너도 재사용된다. 주인만 바꾸면 새
     * 주인의 에이전트가 옛 주인의 파일을 읽고 쓴다. 그룹 공개 검사와 같은 목록을 읽는다.
     */
    public void requireOwnerChangeSafe(String apiBaseUrl, String profileName) {
        if (AgentToolPolicy.hasSandboxToolset(hermesToolsets.readEnabled(apiBaseUrl, profileName))) {
            throw new ApiException(
                    ErrorCode.AGENT_OWNER_CHANGE_REQUIRES_SHELL_OFF,
                    "turn off shell and file toolsets before changing the owner");
        }
    }

    private static String requireName(String name) {
        String stripped = name == null ? "" : name.strip();
        if (stripped.isEmpty() || stripped.length() > MAX_NAME_CHARS) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "an agent name must be 1 to " + MAX_NAME_CHARS + " characters");
        }
        return stripped;
    }

    /** 주인 행을 잠그고 센다. 잠금은 트랜잭션이 끝날 때까지 쥔다. */
    private void requireBelowLimit(CurrentUser user) {
        users.findByIdForUpdate(user.id())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "sign in first"));
        if (agents.countByOwnerUserIdAndDeletedAtIsNullAndConnectorManagedFalse(user.id()) >= properties.maxPerUser()) {
            throw new ApiException(
                    ErrorCode.AGENT_LIMIT_REACHED, "an agent limit of " + properties.maxPerUser() + " was reached");
        }
    }

    /**
     * 번호나 profile 이름이 이미 쓰이는가.
     *
     * <p>profile 이름은 허용 목록과 에이전트 표를 함께 본다. 한쪽만 보면 다른 사람이 쓰던 profile 을 가리켜
     * 격리가 깨진다.
     */
    private boolean isTaken(String code, String profileName) {
        return agents.findByCode(code).isPresent()
                || agents.existsByHermesProfile(profileName)
                || reservedProfileNames.reservedByPerson(profileName);
    }

    /**
     * 에이전트 행을 잠그고 읽는다. 다른 요청이 잠금을 쥐고 있으면 풀릴 때까지 기다린다.
     *
     * <p>트랜잭션에서 그 에이전트를 처음 읽는 자리여야 한다. 먼저 읽어 둔 엔티티가 있으면 기다린 뒤에도 앞의 값이 남는다.
     */
    private Agent lockById(Long agentId) {
        return agents.findByIdForUpdate(agentId).orElseThrow(AgentLifecycleService::notFound);
    }

    /**
     * 고칠 에이전트인지 본다. 주인과 {@code ADMIN} 만 통과한다.
     *
     * <p>{@code ADMIN} 은 읽을 수 없는 남의 비공개 에이전트도 번호로 찾는다. 다른 사용자는 읽을 수 없으면
     * 없는 에이전트와 같게 {@code AGENT_NOT_FOUND}, 읽을 수 있지만 주인이 아니면 {@code FORBIDDEN} 이다.
     */
    private Agent requireManageable(CurrentUser user, Agent agent) {
        requireVisible(user, agent);
        if (!agentService.isEditableBy(user, agent)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "only the owner or an admin can manage this agent");
        }
        return agent;
    }

    /**
     * 지울 에이전트인지 본다. {@link #requireManageable} 과 같되 옛 커넥터 에이전트도 통과한다.
     *
     * <p>{@link AgentService#isEditableBy} 가 옛 커넥터 에이전트를 막는 것은 성격, 스킬, 도구, 공개 범위를 고치지 못하게 하려는
     * 것이다. 지우기에는 적용하지 않는다.
     */
    private static Agent requireDeletable(CurrentUser user, Agent agent) {
        requireVisible(user, agent);
        if (!user.isAdmin() && !Objects.equals(agent.ownerUserId(), user.id())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "only the owner or an admin can manage this agent");
        }
        return agent;
    }

    private static void requireVisible(CurrentUser user, Agent agent) {
        if (agent.isDeleted() || !(user.isAdmin() || agent.isReadableBy(user.id()))) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.AGENT_NOT_FOUND, "no such agent");
    }

    /**
     * 만들다 실패한 profile 을 거둔다.
     *
     * <p>거두기가 실패해도 원래 오류를 가리지 않는다. 부르는 쪽이 원래 오류를 던지고, 거두기 실패는 로그로만
     * 남긴다.
     */
    private void withdraw(String profileName) {
        try {
            provisioner.deprovision(profileName);
        } catch (RuntimeException withdrawFailure) {
            log.error(
                    "에이전트를 만들다 실패해 profile 을 거두려 했으나 그것도 실패했다. 쓰이지 않을 profile 이 남는다 profile={}",
                    profileName,
                    withdrawFailure);
        }
    }

    private static String randomChars() {
        StringBuilder chars = new StringBuilder(RANDOM_CHARS);
        for (int i = 0; i < RANDOM_CHARS; i++) {
            chars.append(RANDOM_ALPHABET.charAt(RANDOM.nextInt(RANDOM_ALPHABET.length())));
        }
        return chars.toString();
    }
}
