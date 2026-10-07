package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.MemoryProposeEnabled;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;

@BackendIntegrationTest
@MemoryProposeEnabled
class ChatMemoryProposalTest {

    @Autowired
    ChatService chat;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    MemoryRepository memories;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    HermesRunsClient hermes;

    @Autowired
    HermesRunEventStream events;

    private CurrentUser user;

    @BeforeEach
    void setUp() {
        memories.deleteAll();
        executions.deleteAll();
        agents.deleteAll();
        users.deleteAll();
        ((StubHermesRunsClient) hermes).reset();
        AppUser saved = users.save(AppUser.of("proposal@example.com", "제안", 1L, UserRole.MEMBER, Instant.now()));
        user = new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.groupId(), saved.role());
        agents.save(Agent.of(
                "proposal",
                "제안",
                "proposal",
                "http://runtime.test",
                CostMode.API,
                CredentialScope.DEDICATED,
                AgentVisibility.PRIVATE,
                user.id(),
                Instant.now()));
        doNothing()
                .when(events)
                .open(
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.any());
    }

    @Test
    @DisplayName("일반 응답 뒤에 제안을 저장하고 자식 실행을 연결한다")
    void savesProposalAfterPlainResponseAndLinksChildRun() {
        runWithProposal();
        chat.send(user, null, "질문", "proposal");
        assertProposalExecution();
    }

    @Test
    @DisplayName("스트리밍 응답 뒤에도 제안을 저장하고 자식 실행을 연결한다")
    void savesProposalAfterStreamingResponseAndLinksChildRun() {
        runWithProposal();
        chat.stream(user, null, "질문", "proposal", event -> {});
        assertProposalExecution();
    }

    @Test
    @DisplayName("모델을 고른 대화의 제안 실행도 그 선택으로 Hermes를 부른다")
    void proposalRunOfConversationWithChosenModelCallsHermesWithThatChoice() {
        runWithProposal();
        Long conversationId = chat.startEmpty(user, "proposal").id();
        chat.chooseModel(user, conversationId, new ModelChoice("nvidia", "example-model-small", "low"));

        chat.send(user, conversationId, "질문", null);

        StubHermesRunsClient stub = (StubHermesRunsClient) hermes;
        assertThat(stub.received()).as("대화 실행과 제안 실행").hasSize(2);
        assertThat(stub.received().getLast()).satisfies(proposal -> {
            assertThat(proposal.provider()).isEqualTo("nvidia");
            assertThat(proposal.model()).isEqualTo("example-model-small");
            assertThat(proposal.reasoningEffort()).isEqualTo("low");
        });
        assertThat(executions.findAll())
                .filteredOn(execution -> execution.parentExecutionId() != null)
                .singleElement()
                .satisfies(child -> assertThat(child.reasoningEffort()).isEqualTo("low"));
    }

    private void runWithProposal() {
        ((StubHermesRunsClient) hermes)
                .willReturnInOrder(
                        HermesRunResult.of(
                                "parent", "session", "completed", "원래 답", "model", "provider", TokenUsage.empty()),
                        HermesRunResult.of(
                                "proposal",
                                "new",
                                "completed",
                                "{\"title\":\"선호\",\"content\":\"국수는 맵지 않게 먹는다\"}",
                                "model",
                                "provider",
                                TokenUsage.empty()));
    }

    private void assertProposalExecution() {
        assertThat(memories.findAll())
                .singleElement()
                .satisfies(memory -> assertThat(memory.status().name()).isEqualTo("PROPOSED"));
        List<AgentExecution> all = executions.findAll();
        assertThat(all).hasSize(2);
        var parent = all.stream()
                .filter(execution -> execution.parentExecutionId() == null)
                .findFirst()
                .orElseThrow();
        var child = all.stream()
                .filter(execution -> execution.parentExecutionId() != null)
                .findFirst()
                .orElseThrow();
        assertThat(child.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(child.parentExecutionId()).isEqualTo(parent.id());
        assertThat(child.rootExecutionId()).isEqualTo(parent.id());
    }
}
