package com.bifos.assistant.chat.nextturn.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.dto.AgentLabel;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.nextturn.application.dto.DelegatedRunActivity;
import com.bifos.assistant.chat.turn.application.ConversationEventHub;
import com.bifos.assistant.chat.turn.application.DelegationLines;
import com.bifos.assistant.chat.turn.application.dto.ChatEvent;
import com.bifos.assistant.chat.turn.application.dto.DelegationLine;
import com.bifos.assistant.runtime.application.dto.RunEvent;
import com.bifos.assistant.runtime.application.dto.RunEventKind;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.usage.execution.application.port.ConnectorToolNames;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DelegationActivityNotifierTest {
    private static final Long CONVERSATION_ID = 7L;
    private static final Long CHILD_ID = 31L;
    private static final UUID PUBLIC_ID = UUID.randomUUID();
    private static final DelegationLine LINE = new DelegationLine(
        CHILD_ID, 30L, ExecutionStatus.RUNNING, new AgentLabel("shopper", "쇼핑 담당", "🛍️", "ROSE"));

    private final DelegationLines lines = mock(DelegationLines.class);
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final ConversationEventHub hub = mock(ConversationEventHub.class);
    private final ConnectorToolNames toolNames = names -> Map.of();
    private final DelegationActivityNotifier notifier = new DelegationActivityNotifier(lines, conversations, hub, toolNames);


    @Test
    @DisplayName("직접 맡긴 실행의 도구 사건은 맡은 에이전트 이름표와 함께 delegation_activity 로 보낸다")
    void publishesDirectDelegationToolEvent() {
        Conversation conversation = mock(Conversation.class);
        when(conversation.publicId()).thenReturn(PUBLIC_ID);
        when(conversations.findById(CONVERSATION_ID)).thenReturn(Optional.of(conversation));
        when(lines.directDelegation(CHILD_ID)).thenReturn(Optional.of(LINE));

        notifier.onActivity(new DelegatedRunActivity(CONVERSATION_ID, 30L, CHILD_ID, tool(RunEventKind.TOOL_COMPLETED, "web_search", "운동화")));

        ArgumentCaptor<ChatEvent> sent = ArgumentCaptor.forClass(ChatEvent.class);
        verify(hub).publish(eq(CONVERSATION_ID), sent.capture());
        ChatEvent event = sent.getValue();
        assertThat(event.type()).isEqualTo("delegation_activity");
        assertThat(event.executionId()).isEqualTo(CHILD_ID);
        assertThat(event.delegation()).isEqualTo(LINE);
        assertThat(event.toolName()).isEqualTo("web_search");
        assertThat(event.phase()).isEqualTo(ChatEvent.COMPLETED);
        assertThat(event.durationMs()).isEqualTo(300L);
        assertThat(event.conversationId()).isEqualTo(PUBLIC_ID);
    }


    @Test
    @DisplayName("맡은 에이전트가 다시 맡긴 손자 위임의 사건은 보내지 않는다")
    void skipsGrandchildDelegation() {
        when(lines.directDelegation(CHILD_ID)).thenReturn(Optional.empty());

        notifier.onActivity(new DelegatedRunActivity(CONVERSATION_ID, 30L, CHILD_ID, tool(RunEventKind.TOOL_STARTED, "web_search", "운동화")));

        verify(hub, never()).publish(any(), any());
    }


    @Test
    @DisplayName("MEMBER 역할에게는 공개하지 않는 도구의 detail 을 비우고 ADMIN 에게는 싣는다")
    void hidesNonPublicToolDetailFromMember() {
        ChatEvent event = DelegationActivityEvents.of(
            PUBLIC_ID, LINE, "terminal", "rm -rf build", ChatEvent.STARTED, null, null, null);

        assertThat(event.forViewer(viewer(UserRole.MEMBER)).orElseThrow().detail()).isNull();
        assertThat(event.forViewer(viewer(UserRole.ADMIN)).orElseThrow().detail()).isEqualTo("rm -rf build");
    }


    private static RunEvent tool(RunEventKind kind, String toolName, String detail) {
        return new RunEvent(kind, null, toolName, detail, 300L, false, null, null, null, null, null, null, null, null,
            null, null, null);
    }


    private static CurrentUser viewer(UserRole role) {
        return new CurrentUser(1L, "viewer@example.com", "viewer", 1L, role);
    }
}
