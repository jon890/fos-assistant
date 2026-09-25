package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.application.StarterService;
import com.bifos.assistant.agent.application.StarterSnapshot;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentStarterPrompt;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.agent.infra.AgentStarterPromptRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.UserRole;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 소개와 추천 질문을 쓰는 규칙과 누가 쓸 수 있는지를 본다.
 *
 * <p>저장소를 실제로 쓴다. 다시 쓸 때 {@code (agent_id, position)} 유일 제약에 걸리지 않는지는 가짜
 * 저장소로는 드러나지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
class StarterServiceTest {

    private static final String OWNED = "starter-owned";
    private static final String SHARED_OWNED = "starter-shared-owned";
    private static final String FAMILY = "starter-family";
    private static final String OTHERS = "starter-others";

    private static final CurrentUser OWNER =
            new CurrentUser(71L, "dad@example.com", "아빠", 1L, UserRole.MEMBER);
    private static final CurrentUser MEMBER =
            new CurrentUser(72L, "kid@example.com", "아이", 1L, UserRole.MEMBER);
    private static final CurrentUser ADMIN =
            new CurrentUser(73L, "admin@example.com", "관리자", 1L, UserRole.ADMIN);

    @Autowired AgentRepository agents;
    @Autowired AgentStarterPromptRepository prompts;
    @Autowired StarterService starters;

    @BeforeEach
    void 준비한다() {
        for (String code : List.of(OWNED, SHARED_OWNED, FAMILY, OTHERS)) {
            agents.findByCode(code).ifPresent(agent -> {
                prompts.deleteAll(prompts.findByAgentIdOrderByPositionAsc(agent.id()));
                agents.delete(agent);
            });
        }
        agents.save(agent(OWNED, AgentVisibility.PRIVATE, OWNER.id()));
        agents.save(agent(SHARED_OWNED, AgentVisibility.FAMILY, OWNER.id()));
        agents.save(agent(FAMILY, AgentVisibility.FAMILY, null));
        agents.save(agent(OTHERS, AgentVisibility.PRIVATE, ADMIN.id()));
    }

    @Test
    void 주인이_쓰면_소개는_공백을_떼고_빈_줄은_버린다() {
        StarterSnapshot written =
                starters.write(OWNER, OWNED, "  집안일을 돕는다  ", List.of("a", " ", "b"));

        assertThat(written.tagline()).isEqualTo("집안일을 돕는다");
        assertThat(written.starterPrompts()).containsExactly("a", "b");
        assertThat(written.editable()).isTrue();

        StarterSnapshot reread = starters.read(OWNER, OWNED);
        assertThat(reread.tagline()).isEqualTo("집안일을 돕는다");
        assertThat(reread.starterPrompts()).containsExactly("a", "b");
        assertThat(agents.findByCode(OWNED).orElseThrow().tagline()).isEqualTo("집안일을 돕는다");
    }

    @Test
    void 다시_쓰면_새_목록만_남고_유일_제약에_걸리지_않는다() {
        starters.write(OWNER, OWNED, "소개", List.of("a", "b"));

        StarterSnapshot rewritten = starters.write(OWNER, OWNED, "소개", List.of("c"));

        assertThat(rewritten.starterPrompts()).containsExactly("c");
        assertThat(storedOf(OWNED))
                .extracting(AgentStarterPrompt::position, AgentStarterPrompt::text)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(0, "c"));
    }

    @Test
    void 비어_있는_소개는_null_로_둔다() {
        starters.write(OWNER, OWNED, "소개", List.of());

        StarterSnapshot cleared = starters.write(OWNER, OWNED, "   ", null);

        assertThat(cleared.tagline()).isNull();
        assertThat(cleared.starterPrompts()).isEmpty();
        assertThat(agents.findByCode(OWNED).orElseThrow().tagline()).isNull();
    }

    @Test
    void 다섯_줄이면_거절하고_쓰지_않는다() {
        starters.write(OWNER, OWNED, "소개", List.of("x"));

        assertCode(
                () -> starters.write(OWNER, OWNED, "새 소개", List.of("a", "b", "c", "d", "e")),
                ErrorCode.VALIDATION_FAILED);

        assertThat(starters.read(OWNER, OWNED).starterPrompts()).containsExactly("x");
        assertThat(agents.findByCode(OWNED).orElseThrow().tagline()).isEqualTo("소개");
    }

    /** 수는 빈 줄을 버린 뒤에 센다. 그래서 빈 줄 하나를 포함한 다섯 줄은 넷으로 통과한다. */
    @Test
    void 빈_줄_하나를_포함한_다섯_줄은_넷이_남는다() {
        StarterSnapshot written = starters.write(OWNER, OWNED, null, List.of("a", "b", "", "c", "d"));

        assertThat(written.starterPrompts()).containsExactly("a", "b", "c", "d");
    }

    @Test
    void null_줄은_빈_줄처럼_버린다() {
        StarterSnapshot written = starters.write(OWNER, OWNED, null, Arrays.asList("a", null, "b"));

        assertThat(written.starterPrompts()).containsExactly("a", "b");
    }

    @Test
    void 상한을_넘는_줄이나_소개는_거절한다() {
        assertCode(
                () -> starters.write(OWNER, OWNED, null, List.of("가".repeat(301))),
                ErrorCode.VALIDATION_FAILED);
        assertCode(
                () -> starters.write(OWNER, OWNED, "가".repeat(201), List.of()),
                ErrorCode.VALIDATION_FAILED);

        assertThat(storedOf(OWNED)).isEmpty();
    }

    @Test
    void 주인이_아닌_MEMBER_가_쓰면_거절하고_쓰지_않는다() {
        assertCode(
                () -> starters.write(MEMBER, SHARED_OWNED, "소개", List.of("a")),
                ErrorCode.FORBIDDEN);

        assertThat(starters.read(MEMBER, SHARED_OWNED).editable()).isFalse();
        assertThat(storedOf(SHARED_OWNED)).isEmpty();
        assertThat(agents.findByCode(SHARED_OWNED).orElseThrow().tagline()).isNull();
    }

    @Test
    void 볼_수_없는_에이전트는_없는_것과_같은_오류를_낸다() {
        assertCode(() -> starters.write(OWNER, OTHERS, "소개", List.of("a")), ErrorCode.AGENT_NOT_FOUND);
        assertCode(() -> starters.read(OWNER, OTHERS), ErrorCode.AGENT_NOT_FOUND);
        assertCode(() -> starters.write(OWNER, "starter-missing", null, List.of()), ErrorCode.AGENT_NOT_FOUND);
    }

    @Test
    void 가족_공개_에이전트는_MEMBER_는_못_쓰고_ADMIN_은_쓴다() {
        assertCode(() -> starters.write(MEMBER, FAMILY, "소개", List.of("a")), ErrorCode.FORBIDDEN);

        StarterSnapshot written = starters.write(ADMIN, FAMILY, "소개", List.of("a"));

        assertThat(written.starterPrompts()).containsExactly("a");
        assertThat(starters.read(MEMBER, FAMILY).starterPrompts()).containsExactly("a");
    }

    @Test
    void 여러_에이전트의_추천_질문을_번호별로_주고_없는_것은_빈_목록이다() {
        starters.write(OWNER, OWNED, null, List.of("a", "b"));
        Agent owned = agents.findByCode(OWNED).orElseThrow();
        Agent family = agents.findByCode(FAMILY).orElseThrow();

        Map<Long, List<String>> byAgent = starters.promptsOf(List.of(owned, family));

        assertThat(byAgent.get(owned.id())).containsExactly("a", "b");
        assertThat(byAgent.get(family.id())).isEmpty();
    }

    @Test
    void 두_저장이_동시에_와도_둘_다_끝나고_한쪽_목록만_남는다() throws Exception {
        starters.write(OWNER, OWNED, "소개", List.of("처음 하나", "처음 둘"));
        List<List<String>> lists = List.of(List.of("왼쪽 하나", "왼쪽 둘"), List.of("오른쪽 하나"));

        for (int round = 0; round < 20; round++) {
            CyclicBarrier start = new CyclicBarrier(lists.size());
            ExecutorService pool = Executors.newFixedThreadPool(lists.size());
            try {
                List<Future<StarterSnapshot>> results = new ArrayList<>();
                for (List<String> list : lists) {
                    results.add(pool.submit(() -> {
                        start.await(5, TimeUnit.SECONDS);
                        return starters.write(OWNER, OWNED, "소개", list);
                    }));
                }
                for (Future<StarterSnapshot> result : results) {
                    // 한쪽이 유일 제약이나 이미 지워진 줄에 걸려 실패하면 여기서 그 예외가 드러난다.
                    result.get(10, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }

            List<String> stored = storedOf(OWNED).stream().map(AgentStarterPrompt::text).toList();
            assertThat(lists).as("%d번째 동시 저장 뒤 남은 목록", round).contains(stored);
        }
    }

    private List<AgentStarterPrompt> storedOf(String code) {
        return prompts.findByAgentIdOrderByPositionAsc(agents.findByCode(code).orElseThrow().id());
    }

    private static void assertCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(expected);
    }

    private static Agent agent(String code, AgentVisibility visibility, Long ownerUserId) {
        return Agent.of(code, code, code, "http://agent-runtime.test/p/" + code, "openai-codex",
                "example-model", CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, visibility,
                ownerUserId);
    }
}
