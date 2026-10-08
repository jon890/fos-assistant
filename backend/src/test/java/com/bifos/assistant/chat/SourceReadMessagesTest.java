package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.application.ModelTierService;
import com.bifos.assistant.chat.application.SourceReadSummaries;
import com.bifos.assistant.chat.application.SourceReadSummary;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.presentation.ChatController;
import com.bifos.assistant.chat.presentation.ChatDtos.MessageView;
import com.bifos.assistant.chat.presentation.ChatEventStreams;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.application.UserDisplayNameService;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SourceReadMessagesTest {
    private final ChatService chat = mock(ChatService.class);
    private final ConversationAccess access = mock(ConversationAccess.class);
    private final SourceReadSummaries sourceReads = mock(SourceReadSummaries.class);
    private final CurrentUser user = new CurrentUser(1L, "user@example.com", "사용자", 1L, UserRole.MEMBER);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final ChatController controller = new ChatController(
            chat,
            currentUser,
            mock(UserDisplayNameService.class),
            null,
            access,
            new ChatEventStreams(Duration.ofSeconds(1)),
            null,
            mock(ModelTierService.class),
            List.of(),
            sourceReads);

    @Test
    @DisplayName("비서 답에만 원문 열람 요약을 붙이고 실행 번호 없는 답은 미확인 요약을 쓴다")
    void attachesAssistantSummaryAndLeavesUserAndSystemNull() throws Exception {
        UUID conversationId = UUID.randomUUID();
        ChatMessage userMessage = ChatMessage.fromUser(4L, user.id(), "질문", Instant.EPOCH);
        ChatMessage assistant = ChatMessage.fromAssistant(4L, "답", null, Instant.EPOCH);
        ChatMessage system = ChatMessage.fromSystem(4L, "알림", Instant.EPOCH);
        set(userMessage, "id", 10L);
        set(assistant, "id", 11L);
        set(system, "id", 12L);
        when(currentUser.require()).thenReturn(user);
        when(access.requireOwnId(user, conversationId)).thenReturn(4L);
        when(chat.history(user, 4L)).thenReturn(List.of(userMessage, assistant, system));
        when(chat.executionIdsHavingChildren(any())).thenReturn(Set.of());
        when(chat.switchedLabels(any(), any())).thenReturn(Map.of());
        when(chat.activitySummaries(any())).thenReturn(Map.of());
        when(chat.statuses(any())).thenReturn(Map.of());
        when(chat.attachmentsByMessage(user, 4L)).thenReturn(Map.of());
        when(chat.artifactsByMessage(any())).thenReturn(Map.of());
        when(chat.deliveryStates(4L)).thenReturn(Map.of());
        SourceReadSummary missing = new SourceReadSummary(0, List.of(), 0, false);
        when(sourceReads.of(List.of(userMessage, assistant, system))).thenReturn(Map.of(11L, missing));

        List<MessageView> result = controller.messages(conversationId);

        assertThat(result).extracting(MessageView::sourceReads).containsExactly(null, missing, null);
    }

    @Test
    @DisplayName("남의 대화는 원문 열람 집계 전에 거절한다")
    void rejectsOtherConversationBeforeReadingSourceSummaries() {
        UUID conversationId = UUID.randomUUID();
        when(currentUser.require()).thenReturn(user);
        when(access.requireOwnId(user, conversationId))
                .thenThrow(new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "missing"));

        assertThatThrownBy(() -> controller.messages(conversationId)).isInstanceOf(ApiException.class);

        verify(sourceReads, never()).of(any());
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
