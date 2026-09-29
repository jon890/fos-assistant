package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentLifecycleService;
import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.HermesDashboardClient;
import com.bifos.assistant.hermes.HermesProfileKeyStore;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.mcp.domain.AgentToken;
import com.bifos.assistant.mcp.infra.AgentTokenRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 사용자가 에이전트를 만들고, 공개 범위를 바꾸고, 지우는 순서를 본다.
 *
 * <p>테스트 클래스에 트랜잭션을 두지 않는다. 서비스의 트랜잭션과 주인 행 잠금이 실제로 돌아야 동시 요청이
 * 차례로 세어지는지 볼 수 있다. Hermes 대시보드와 도구 목록만 대역이고, profile 을 만드는 순서와 토큰
 * 발급, key 파일은 실제 bean 이다.
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentLifecycleServiceTest {

    /** plugin 틀이 붙이는 안전한 기본 도구다. 셸과 파일 등급이 없다. */
    private static final List<String> SAFE_TOOLSETS = List.of("web", "skills", "todo", "vision");

    private static final List<String> WITH_TERMINAL = List.of("web", "terminal");

    private static final String MCP_TOKEN_ENV = "MCP_FOS_ASSISTANT_API_KEY";

    /** {@code application-test.yml} 의 공유 listener 주소다. */
    private static final String LISTENER = "https://hermes-listener.example.com";

    @Autowired AgentLifecycleService lifecycle;
    @Autowired AgentService agentService;
    @Autowired AgentRepository agents;
    @Autowired AppUserRepository users;
    @Autowired AgentTokenRepository tokens;
    @Autowired HermesProfileKeyStore keyStore;

    @MockitoBean HermesDashboardClient dashboard;
    @MockitoBean HermesToolsetClient toolsets;

    /** 이 테스트가 실제 key 디렉터리에 남긴 파일을 지우려고 적어 둔다. */
    private final List<String> createdProfiles = new ArrayList<>();

    @BeforeEach
    void 준비한다() {
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(SAFE_TOOLSETS);
    }

    @AfterEach
    void key_파일을_치운다() {
        createdProfiles.forEach(keyStore::delete);
    }

    @Test
    void 만들면_profile_과_토큰이_생기고_요청자가_주인인_관리_대상_행이_남는다() {
        CurrentUser kid = member();

        Agent created = create(kid, "  숙제 도우미  ", null);

        String profile = created.hermesProfile();
        assertThat(created.code()).matches("a[a-z0-9]{10}");
        assertThat(profile).matches("ua-[a-z0-9]{10}");
        verify(dashboard).createProfile(profile);
        verify(dashboard).putEnv(eq(profile), eq(MCP_TOKEN_ENV), anyString());
        verify(toolsets).readEnabled(LISTENER + "/p/" + profile, profile);
        assertThat(tokens.findByProfileNameAndRevokedAtIsNull(profile))
                .as("profile %s 에 묶인 폐기 안 된 토큰", profile)
                .hasSize(1);

        Agent stored = agents.findByCode(created.code()).orElseThrow();
        assertThat(stored.profileManaged()).isTrue();
        assertThat(stored.ownerUserId()).isEqualTo(kid.id());
        assertThat(stored.name()).isEqualTo("숙제 도우미");
        assertThat(stored.visibility()).isEqualTo(AgentVisibility.PRIVATE);
        assertThat(stored.credentialScope()).isEqualTo(CredentialScope.SHARED_HOUSEHOLD);
        assertThat(stored.apiBaseUrl()).isEqualTo(LISTENER + "/p/" + profile);
        assertThat(stored.isDeleted()).isFalse();
    }

    @Test
    void 그룹_공개로_만들면_그룹에_보이고_주인은_요청자다() {
        CurrentUser kid = member();
        CurrentUser other = member();

        Agent created = create(kid, "가족 요리사", AgentVisibility.GROUP);

        assertThat(created.visibility()).isEqualTo(AgentVisibility.GROUP);
        assertThat(created.ownerUserId()).isEqualTo(kid.id());
        assertThat(agentService.readableBy(other)).extracting(Agent::code).contains(created.code());
    }

    @Test
    void 이미_다섯인_MEMBER_는_만들지_못하고_Hermes_를_부르지_않는다() {
        CurrentUser kid = member();
        seedAgents(kid, 5);

        assertCode(() -> lifecycle.create(kid, "여섯째", null), ErrorCode.AGENT_LIMIT_REACHED);

        verifyNoInteractions(dashboard);
        assertThat(agents.countByOwnerUserIdAndDeletedAtIsNull(kid.id())).isEqualTo(5);
    }

    @Test
    void ADMIN_은_다섯이어도_만든다() {
        CurrentUser admin = admin();
        seedAgents(admin, 5);

        Agent created = create(admin, "여섯째", null);

        assertThat(created.ownerUserId()).isEqualTo(admin.id());
        assertThat(agents.countByOwnerUserIdAndDeletedAtIsNull(admin.id())).isEqualTo(6);
    }

    @Test
    void 지운_에이전트는_상한에_세지_않는다() {
        CurrentUser kid = member();
        List<Agent> seeded = seedAgents(kid, 5);
        Agent removed = seeded.get(0);
        removed.markDeleted(Instant.parse("2026-09-01T00:00:00Z"));
        agents.save(removed);

        Agent created = create(kid, "다시 다섯째", null);

        assertThat(created.ownerUserId()).isEqualTo(kid.id());
        assertThat(agents.countByOwnerUserIdAndDeletedAtIsNull(kid.id())).isEqualTo(5);
    }

    /**
     * 같은 사람이 두 번 눌러도 상한을 넘지 않는다.
     *
     * <p>넷을 가진 사용자로 두 요청을 한꺼번에 보낸다. 주인 행 잠금이 없으면 둘 다 넷을 세고 여섯이 된다.
     * 스레드가 엇갈리는 차례는 매번 달라서 회차마다 새 사용자로 여러 번 돌린다.
     */
    @Test
    void 넷인_사용자가_동시에_두_번_만들면_하나만_성공하고_하나는_상한이다() throws Exception {
        for (int round = 0; round < 20; round++) {
            CurrentUser kid = member();
            seedAgents(kid, 4);
            CyclicBarrier start = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Object> outcomes = new ArrayList<>();
            try {
                List<Future<Agent>> results = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    results.add(pool.submit(() -> {
                        start.await(5, TimeUnit.SECONDS);
                        return lifecycle.create(kid, "동시에", null);
                    }));
                }
                for (Future<Agent> result : results) {
                    outcomes.add(outcomeOf(result));
                }
            } finally {
                pool.shutdownNow();
            }

            assertThat(outcomes)
                    .as("%d번째 동시 만들기의 결과", round)
                    .filteredOn(Agent.class::isInstance)
                    .hasSize(1);
            assertThat(outcomes)
                    .as("%d번째 동시 만들기의 결과", round)
                    .filteredOn(outcome -> outcome == ErrorCode.AGENT_LIMIT_REACHED)
                    .hasSize(1);
            assertThat(agents.countByOwnerUserIdAndDeletedAtIsNull(kid.id()))
                    .as("%d번째 동시 만들기 뒤의 에이전트 수", round)
                    .isEqualTo(5);
        }
    }

    @Test
    void 만든_profile_에_셸_도구가_켜져_있으면_거두고_행을_남기지_않는다() {
        CurrentUser kid = member();
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(WITH_TERMINAL);
        List<String> made = new ArrayList<>();
        doAnswer(call -> {
            made.add(call.getArgument(0));
            return null;
        }).when(dashboard).createProfile(anyString());

        assertCode(() -> lifecycle.create(kid, "셸이 켜진 틀", null), ErrorCode.HERMES_PROVISION_FAILED);

        assertThat(made).hasSize(1);
        String profile = made.get(0);
        createdProfiles.add(profile);
        verify(dashboard).deleteProfile(profile);
        assertThat(tokens.findByProfileNameAndRevokedAtIsNull(profile)).isEmpty();
        assertThat(tokensOf(profile))
                .as("발급했다가 폐기한 토큰")
                .isNotEmpty()
                .allSatisfy(token -> assertThat(token.revokedAt()).isNotNull());
        assertThat(agents.existsByHermesProfile(profile)).isFalse();
        assertThat(agents.countByOwnerUserIdAndDeletedAtIsNull(kid.id())).isZero();
    }

    @Test
    void 그룹으로_바꿔도_주인이_남는다() {
        CurrentUser kid = member();
        Agent created = create(kid, "숙제 도우미", null);

        Agent changed = lifecycle.changeVisibility(kid, created.code(), AgentVisibility.GROUP);

        assertThat(changed.visibility()).isEqualTo(AgentVisibility.GROUP);
        Agent stored = agents.findByCode(created.code()).orElseThrow();
        assertThat(stored.visibility()).isEqualTo(AgentVisibility.GROUP);
        assertThat(stored.ownerUserId()).isEqualTo(kid.id());
    }

    @Test
    void 셸_도구가_켜진_에이전트는_그룹으로_바꾸지_못한다() {
        CurrentUser kid = member();
        Agent created = create(kid, "숙제 도우미", null);
        when(toolsets.readEnabled(created.apiBaseUrl(), created.hermesProfile())).thenReturn(WITH_TERMINAL);

        assertCode(
                () -> lifecycle.changeVisibility(kid, created.code(), AgentVisibility.GROUP),
                ErrorCode.AGENT_TOOLS_REQUIRE_PRIVATE);

        assertThat(agents.findByCode(created.code()).orElseThrow().visibility())
                .isEqualTo(AgentVisibility.PRIVATE);
    }

    @Test
    void 공개_범위가_비면_거절한다() {
        CurrentUser kid = member();
        Agent created = create(kid, "숙제 도우미", null);

        assertCode(() -> lifecycle.changeVisibility(kid, created.code(), null), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void 다른_MEMBER_는_그룹_에이전트를_바꾸거나_지우지_못하고_비공개는_없는_것과_같다() {
        CurrentUser kid = member();
        CurrentUser other = member();
        Agent shared = create(kid, "가족 요리사", AgentVisibility.GROUP);
        Agent secret = create(kid, "비밀 일기", null);

        assertCode(
                () -> lifecycle.changeVisibility(other, shared.code(), AgentVisibility.PRIVATE),
                ErrorCode.FORBIDDEN);
        assertCode(() -> lifecycle.delete(other, shared.code()), ErrorCode.FORBIDDEN);
        assertCode(
                () -> lifecycle.changeVisibility(other, secret.code(), AgentVisibility.GROUP),
                ErrorCode.AGENT_NOT_FOUND);
        assertCode(() -> lifecycle.delete(other, secret.code()), ErrorCode.AGENT_NOT_FOUND);

        assertThat(agents.findByCode(shared.code()).orElseThrow().visibility()).isEqualTo(AgentVisibility.GROUP);
        assertThat(agents.findByCode(shared.code()).orElseThrow().isDeleted()).isFalse();
        assertThat(agents.findByCode(secret.code()).orElseThrow().isDeleted()).isFalse();
        verify(dashboard, never()).deleteProfile(anyString());
    }

    @Test
    void ADMIN_은_다른_사람의_비공개_에이전트를_지운다() {
        CurrentUser kid = member();
        Agent secret = create(kid, "비밀 일기", null);

        lifecycle.delete(admin(), secret.code());

        assertThat(agents.findByCode(secret.code()).orElseThrow().isDeleted()).isTrue();
        verify(dashboard).deleteProfile(secret.hermesProfile());
    }

    @Test
    void 만든_profile_의_에이전트를_지우면_profile_을_거두고_토큰을_폐기한다() {
        CurrentUser kid = member();
        Agent created = create(kid, "숙제 도우미", null);

        lifecycle.delete(kid, created.code());

        verify(dashboard).deleteProfile(created.hermesProfile());
        assertThat(tokens.findByProfileNameAndRevokedAtIsNull(created.hermesProfile())).isEmpty();
        Agent stored = agents.findByCode(created.code()).orElseThrow();
        assertThat(stored.isDeleted()).isTrue();
        assertThat(stored.enabled()).isFalse();
        assertCode(() -> agentService.requireStartable(kid, created.code()), ErrorCode.AGENT_NOT_FOUND);
    }

    @Test
    void 운영에서_만든_profile_의_에이전트를_지우면_profile_을_남긴다() {
        CurrentUser kid = member();
        Agent seeded = seedAgents(kid, 1).get(0);

        lifecycle.delete(kid, seeded.code());

        verify(dashboard, never()).deleteProfile(anyString());
        assertThat(agents.findByCode(seeded.code()).orElseThrow().isDeleted()).isTrue();
        assertCode(() -> agentService.requireStartable(kid, seeded.code()), ErrorCode.AGENT_NOT_FOUND);
    }

    @Test
    void profile_을_지우지_못하면_에이전트를_지우지_않는다() {
        CurrentUser kid = member();
        Agent created = create(kid, "숙제 도우미", null);
        doThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "dashboard is down"))
                .when(dashboard).deleteProfile(created.hermesProfile());

        assertCode(() -> lifecycle.delete(kid, created.code()), ErrorCode.HERMES_UNAVAILABLE);

        Agent stored = agents.findByCode(created.code()).orElseThrow();
        assertThat(stored.isDeleted()).isFalse();
        assertThat(stored.enabled()).isTrue();
    }

    @Test
    void 이름이_공백뿐이거나_101자면_거절하고_100자는_된다() {
        CurrentUser kid = member();

        assertCode(() -> lifecycle.create(kid, "   ", null), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> lifecycle.create(kid, "가".repeat(101), null), ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(dashboard);

        Agent created = create(kid, "가".repeat(100), null);

        assertThat(agents.findByCode(created.code()).orElseThrow().name()).isEqualTo("가".repeat(100));
    }

    private Agent create(CurrentUser user, String name, AgentVisibility visibility) {
        Agent created = lifecycle.create(user, name, visibility);
        createdProfiles.add(created.hermesProfile());
        return created;
    }

    /** 운영에서 만든 profile 을 가리키는 에이전트처럼 행만 넣는다. 관리 대상 표시가 없다. */
    private List<Agent> seedAgents(CurrentUser owner, int count) {
        List<Agent> seeded = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String code = "seed-" + UUID.randomUUID().toString().substring(0, 13);
            seeded.add(agents.save(Agent.of(code, code, code, "http://agent-runtime.test/p/" + code,
                    CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE,
                    owner.id())));
        }
        return seeded;
    }

    private CurrentUser member() {
        return user(UserRole.MEMBER);
    }

    private CurrentUser admin() {
        return user(UserRole.ADMIN);
    }

    private CurrentUser user(UserRole role) {
        String email = "lifecycle-" + UUID.randomUUID() + "@example.com";
        AppUser saved = users.save(AppUser.of(email, email, 1L, role));
        return new CurrentUser(saved.id(), email, email, 1L, role);
    }

    private List<AgentToken> tokensOf(String profile) {
        return tokens.findAll().stream().filter(token -> profile.equals(token.profileName())).toList();
    }

    /** 성공이면 만든 에이전트, 거절이면 그 오류 코드다. 그 밖의 실패는 그대로 드러낸다. */
    private Object outcomeOf(Future<Agent> result) throws Exception {
        try {
            Agent created = result.get(10, TimeUnit.SECONDS);
            createdProfiles.add(created.hermesProfile());
            return created;
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof ApiException api) {
                return api.code();
            }
            throw failure;
        }
    }

    private static void assertCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(expected);
    }
}
