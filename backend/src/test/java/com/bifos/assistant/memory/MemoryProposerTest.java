package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.memory.application.MemoryProposer;
import com.bifos.assistant.memory.application.MemoryProposalProperties;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "assistant.memory.propose.enabled=true")
@ActiveProfiles("test")
@Import(MemoryProposerTest.StubRuntime.class)
class MemoryProposerTest {

    @TestConfiguration
    static class StubRuntime {
        @Bean @Primary StubHermesRunsClient stubHermesRunsClient() { return new StubHermesRunsClient(); }
    }

    private static final CurrentUser USER = new CurrentUser(1L, "user@example.com", "user", 1L, UserRole.MEMBER);
    @Autowired MemoryProposer proposer;
    @Autowired ExecutionRecorder recorder;
    @Autowired AgentRepository agents;
    @Autowired ConversationRepository conversations;
    @Autowired AgentExecutionRepository executions;
    @Autowired MemoryRepository memories;
    @Autowired HermesRunsClient hermes;
    private Agent agent;
    private Conversation conversation;

    @BeforeEach
    void 준비한다() {
        memories.deleteAll(); executions.deleteAll(); conversations.deleteAll(); agents.deleteAll();
        ((StubHermesRunsClient) hermes).reset();
        agent = agents.save(Agent.of("test", "검사", "test", "http://runtime.test", "provider", "model", CostMode.API, CredentialScope.DEDICATED, AgentVisibility.PRIVATE, USER.id()));
        conversation = conversations.save(Conversation.startedBy(USER.id(), "대화", agent.id()));
    }

    @Test
    void 제안을_만들면_부모와_뿌리_실행을_기록하고_PROPOSED로_저장한다() {
        AgentExecution parent = recorder.start(USER, conversation, agent, null, null, 0L);
        ((StubHermesRunsClient) hermes).willReturn(new HermesRunResult("proposal", "new", "completed", "{\"title\":\"선호\",\"content\":\"국수는 맵지 않게 먹는다\"}", "model", "provider", TokenUsage.empty()));

        proposer.proposeFrom(USER, conversation, agent, parent, "국수 이야기");

        assertThat(memories.findAll()).singleElement().satisfies(memory -> {
            assertThat(memory.status().name()).isEqualTo("PROPOSED");
            assertThat(memory.proposedByExecutionId()).isNotNull();
        });
        assertThat(executions.findAll()).filteredOn(execution -> !execution.id().equals(parent.id())).singleElement().satisfies(child -> {
            assertThat(child.parentExecutionId()).isEqualTo(parent.id());
            assertThat(child.rootExecutionId()).isEqualTo(parent.id());
            assertThat(child.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        });
    }

    @Test
    void 제안_실행이_실패해도_예외를_던지지_않고_자식_실행은_실패로_남긴다() {
        AgentExecution parent = recorder.start(USER, conversation, agent, null, null, 0L);
        ((StubHermesRunsClient) hermes).willFail(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"));

        proposer.proposeFrom(USER, conversation, agent, parent, "답");

        assertThat(memories.findAll()).isEmpty();
        assertThat(executions.findAll()).filteredOn(execution -> !execution.id().equals(parent.id())).singleElement().satisfies(child -> {
            assertThat(child.status()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(child.parentExecutionId()).isEqualTo(parent.id());
            assertThat(child.rootExecutionId()).isEqualTo(parent.id());
        });
    }

    @Test
    void NONE과_잘못된_JSON은_Memory를_만들지_않는다() {
        AgentExecution parent = recorder.start(USER, conversation, agent, null, null, 0L);
        ((StubHermesRunsClient) hermes).willReturn(new HermesRunResult("proposal", "new", "completed", "NONE", "model", "provider", TokenUsage.empty()));
        proposer.proposeFrom(USER, conversation, agent, parent, "답");
        assertThat(memories.findAll()).isEmpty();
    }

    @Test
    void 자식_실행을_시작하지_못해도_원래_대화에_예외를_전하지_않는다() {
        ExecutionRecorder failingRecorder = mock(ExecutionRecorder.class);
        doThrow(new IllegalStateException("database unavailable")).when(failingRecorder)
                .start(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
        MemoryProposer isolated = new MemoryProposer(new MemoryProposalProperties(true),
                mock(MemoryService.class), mock(HermesRunsClient.class), failingRecorder, new ObjectMapper());
        AgentExecution parent = recorder.start(USER, conversation, agent, null, null, 0L);

        assertThatCode(() -> isolated.proposeFrom(USER, conversation, agent, parent, "답"))
                .doesNotThrowAnyException();
    }
}
