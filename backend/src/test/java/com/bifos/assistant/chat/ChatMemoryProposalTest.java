package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "assistant.memory.propose.enabled=true")
@ActiveProfiles("test")
@Import(ChatMemoryProposalTest.StubRuntime.class)
class ChatMemoryProposalTest {

    @TestConfiguration
    static class StubRuntime {
        @Bean @Primary StubHermesRunsClient runs() { return new StubHermesRunsClient(); }
        @Bean @Primary HermesRunEventStream events() { return mock(HermesRunEventStream.class); }
    }

    @Autowired ChatService chat;
    @Autowired AppUserRepository users;
    @Autowired AgentRepository agents;
    @Autowired MemoryRepository memories;
    @Autowired AgentExecutionRepository executions;
    @Autowired HermesRunsClient hermes;
    @Autowired HermesRunEventStream events;
    private CurrentUser user;

    @BeforeEach
    void 준비한다() {
        memories.deleteAll(); executions.deleteAll(); agents.deleteAll(); users.deleteAll();
        ((StubHermesRunsClient) hermes).reset();
        AppUser saved = users.save(AppUser.of("proposal@example.com", "제안", 1L, UserRole.MEMBER));
        user = new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.familyId(), saved.role());
        agents.save(Agent.of("proposal", "제안", "proposal", "http://runtime.test", "provider", "model", CostMode.API, CredentialScope.DEDICATED, AgentVisibility.PRIVATE, user.id()));
        doNothing().when(events).open(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 일반_응답_뒤에_제안을_저장하고_자식_실행을_연결한다() {
        runWithProposal();
        chat.send(user, null, "질문", "proposal");
        assertProposalExecution();
    }

    @Test
    void 스트리밍_응답_뒤에도_제안을_저장하고_자식_실행을_연결한다() {
        runWithProposal();
        chat.stream(user, null, "질문", "proposal", event -> { });
        assertProposalExecution();
    }

    private void runWithProposal() {
        ((StubHermesRunsClient) hermes).willReturnInOrder(
                new HermesRunResult("parent", "session", "completed", "원래 답", "model", "provider", TokenUsage.empty()),
                new HermesRunResult("proposal", "new", "completed", "{\"title\":\"선호\",\"content\":\"국수는 맵지 않게 먹는다\"}", "model", "provider", TokenUsage.empty()));
    }

    private void assertProposalExecution() {
        assertThat(memories.findAll()).singleElement().satisfies(memory -> assertThat(memory.status().name()).isEqualTo("PROPOSED"));
        List<com.bifos.assistant.usage.domain.AgentExecution> all = executions.findAll();
        assertThat(all).hasSize(2);
        var parent = all.stream().filter(execution -> execution.parentExecutionId() == null).findFirst().orElseThrow();
        var child = all.stream().filter(execution -> execution.parentExecutionId() != null).findFirst().orElseThrow();
        assertThat(child.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(child.parentExecutionId()).isEqualTo(parent.id());
        assertThat(child.rootExecutionId()).isEqualTo(parent.id());
    }
}
