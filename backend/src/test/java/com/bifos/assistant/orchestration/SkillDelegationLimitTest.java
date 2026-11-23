package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.turn.application.ChatService;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.application.dto.HermesRunResult;
import com.bifos.assistant.hermes.application.port.HermesRunsClient;
import com.bifos.assistant.orchestration.application.SkillDelegations;
import com.bifos.assistant.runtime.domain.TokenUsage;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.library.application.SkillService;
import com.bifos.assistant.skill.library.application.dto.SkillList;
import com.bifos.assistant.skill.library.application.dto.SkillListItem;
import com.bifos.assistant.skill.library.application.dto.SkillSource;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.SmallExecutionLimit;
import com.bifos.assistant.testsupport.TestPrimaryAgents;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.execution.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 스킬 맡기기도 사용자 동시 실행 한도(ADR-069)로 센다(docs/features/assistant-hub/spec.md 의 REQ10). 맡긴 실행은 turn 자리를 쥐지 않아
 * 따로 세지 않으면 한 사람이 서버 전체 위임 한도까지 채운다. 한도는 2 다.
 */
@BackendIntegrationTest
@SmallExecutionLimit
class SkillDelegationLimitTest {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);
    private static final String TEXT = "/커리어 · position-recommender 이번 주 공고";

    @Autowired
    SkillDelegations delegations;

    @Autowired
    ChatService chat;

    @Autowired
    SkillService skillService;

    @Autowired
    TestPrimaryAgents primaries;

    @Autowired
    HermesRunsClient hermes;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ExecutionEventRepository executionEvents;

    private final CountDownLatch careerRelease = new CountDownLatch(1);
    private CurrentUser dad;


    @BeforeEach
    void setUp() {
        clean();
        StubHermesRunsClient stub = (StubHermesRunsClient)hermes;
        stub.reset();
        stub.willAnswer(command -> {
            if (command.profileName().equals("career")) {
                await(careerRelease);
            }
            return HermesRunResult.of(
                "run-" + command.profileName(), command.sessionId(), "completed", "답", "model", "provider", TokenUsage.empty());
        });
        AppUser user = users.save(AppUser.of("skill-delegation-limit@example.com", "아빠", 1L, UserRole.MEMBER));
        dad = new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
        primaries.make(dad, agents.save(agent("dad", "비서", user.id())));
        agents.save(agent("career", "커리어", user.id()));
        doReturn(new SkillList(List.of(new SkillListItem("position-recommender", "", SkillSource.UPLOADED, true, null)), false, true, 30))
            .when(skillService)
            .commandList(argThat(agent -> agent != null && "career".equals(agent.code())));
    }


    @AfterEach
    void tearDown() {
        careerRelease.countDown();
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (executions.findAll().stream().anyMatch(row -> row.status() == ExecutionStatus.RUNNING) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        clean();
    }


    @Test
    @DisplayName("도는 스킬 맡기기가 사용자 자리를 차지해 한도를 넘는 다음 맡기기는 USER_BUSY 이고 질문을 남기지 않는다")
    void countsTowardUserLimit() {
        Long conversationId = chat.startEmpty(dad, null).id();
        delegations.delegate(dad, conversationId, "career", TEXT);
        delegations.delegate(dad, conversationId, "career", TEXT);
        long saved = messages.count();

        assertThatThrownBy(() -> delegations.delegate(dad, conversationId, "career", TEXT))
            .isInstanceOf(ApiException.class)
            .extracting(ex -> ((ApiException)ex).code())
            .isEqualTo(ErrorCode.USER_BUSY);
        assertThat(messages.count()).isEqualTo(saved);
        assertThat(executions.findAll()).hasSize(2);
    }


    private void clean() {
        executionEvents.deleteAll();
        executions.deleteAll();
        messages.deleteAll();
        conversations.deleteAll();
        agents.deleteAll();
        users.deleteAll();
    }


    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS)) {
                fail("커리어 실행을 풀지 않았다");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }


    private static Agent agent(String code, String name, Long ownerId) {
        return Agent.of(
            code, name, code, "http://agent-runtime.test/p/" + code, CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.PRIVATE, ownerId);
    }
}
