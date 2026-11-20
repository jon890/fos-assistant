package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.artifact.application.ArtifactService;
import com.bifos.assistant.chat.attachment.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.turn.application.ChatService;
import com.bifos.assistant.chat.turn.application.dto.ChatTurn;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.application.dto.HermesRunCommand;
import com.bifos.assistant.hermes.application.dto.HermesRunResult;
import com.bifos.assistant.hermes.application.port.HermesRunsClient;
import com.bifos.assistant.hermes.application.port.HermesToolsetClient;
import com.bifos.assistant.hermes.domain.TokenUsage;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.execution.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 셸·파일 도구가 실행 공간에서 도는 에이전트의 turn 이 파일 공간에 넣을 파일을 {@code /workspace/} 에 만들라는 실행 입력을 받는 것을
 * 확인한다. 실행 공간이 없는 에이전트와 켜진 도구를 읽지 못한 turn 은 결과물 폴더 규칙 그대로다.
 *
 * <p>문구는 {@code ArtifactTest} 와 {@code FileSpaceTurnTest} 가 본다. 여기서는 turn 이 어느 쪽 단락을 고르는지만 본다.
 */
@BackendIntegrationTest
class SandboxFileSpaceTurnTest {

    private static final String QUESTION = "메모를 md 로 만들어 내 파일 공간에 넣어 줘";

    @Autowired
    ChatService chat;

    @Autowired
    ArtifactService artifactService;

    @Autowired
    HermesToolsetClient toolsets;

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
    ChatAttachmentRepository attachmentRows;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ExecutionEventRepository executionEvents;

    @Autowired
    MemoryRepository memories;


    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient)hermes;
    }


    @BeforeEach
    void reset() {
        stub().reset();
        executionEvents.deleteAll();
        executions.deleteAll();
        attachmentRows.deleteAll();
        messages.deleteAll();
        agents.deleteAll();
        memories.deleteAll();
        users.deleteAll();
    }


    @Test
    @DisplayName("셸·파일 도구가 실행 공간에서 도는 에이전트는 /workspace/ 에 만들어 넣으라는 단락을 받는다")
    void sandboxAgentGetsWorkspaceRule() {
        CurrentUser dad = member("sandbox-dad@example.com", "sandbox-dad");
        when(toolsets.readEnabled(any(), any())).thenReturn(List.of("fos-assistant", "terminal", "file"));

        String input = sentInput(dad, "sandbox-dad");

        assertThat(input).contains("/workspace/ 아래에 만들고 file_space_put 의 path 에 그 /workspace/ 경로를 그대로 쓴다")
            .contains("위 폴더의 결과물은 artifact_write 로만 저장한다")
            .doesNotContain("위 폴더에 결과물 파일을 직접 쓴다");
    }


    @Test
    @DisplayName("실행 공간 도구가 없는 에이전트는 지금처럼 결과물 폴더 단락을 받는다")
    void agentWithoutSandboxKeepsFolderRule() {
        CurrentUser dad = member("plain-dad@example.com", "plain-dad");
        when(toolsets.readEnabled(any(), any())).thenReturn(List.of("fos-assistant", "web"));

        stub().willReturnInOrder(reply());
        ChatTurn turn = chat.send(dad, null, QUESTION, "plain-dad");

        assertThat(stub().received()).extracting(HermesRunCommand::input).containsExactly(
            artifactService.agentPreamble(conversations.findById(turn.conversationId()).orElseThrow(), false) + QUESTION);
    }


    @Test
    @DisplayName("켜진 도구를 읽지 못해도 turn 은 결과물 폴더 단락으로 끝난다")
    void unreadableToolsetsFallBackToFolderRule() {
        CurrentUser dad = member("down-dad@example.com", "down-dad");
        when(toolsets.readEnabled(any(), any())).thenThrow(new IllegalStateException("listener down"));

        String input = sentInput(dad, "down-dad");

        assertThat(input).contains("위 폴더에 결과물 파일을 직접 쓴다").doesNotContain("/workspace/ 아래에 만들고");
    }


    private String sentInput(CurrentUser user, String profile) {
        stub().willReturnInOrder(reply());
        chat.send(user, null, QUESTION, profile);
        assertThat(stub().received()).hasSize(1);
        return stub().received().getFirst().input();
    }


    private static HermesRunResult reply() {
        return HermesRunResult.of("run-1", "session", "completed", "넣었어요.", "model", "provider", TokenUsage.empty());
    }


    private CurrentUser member(String email, String profileName) {
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER));
        agents.save(Agent.of(
            profileName,
            profileName,
            profileName,
            "http://agent-runtime.test/p/" + profileName,
            CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.PRIVATE,
            user.id()));
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }
}
