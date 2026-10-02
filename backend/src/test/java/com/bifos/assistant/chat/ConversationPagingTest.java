package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ConversationPage;
import com.bifos.assistant.chat.application.ConversationWriter;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.type.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 대화 목록을 cursor 로 쪽마다 읽는 동작을 본다. */
@SpringBootTest
@ActiveProfiles("test")
class ConversationPagingTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    ChatService chat;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ConversationWriter conversationWriter;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ExecutionEventRepository executionEvents;

    @Autowired
    AgentRepository agents;

    @Autowired
    MemoryRepository memories;

    @Autowired
    AppUserRepository users;

    @BeforeEach
    void reset() {
        executionEvents.deleteAll();
        executions.deleteAll();
        messages.deleteAll();
        conversations.deleteAll();
        agents.deleteAll();
        memories.deleteAll();
        users.deleteAll();
    }

    private CurrentUser member(String name) {
        AppUser user = users.save(AppUser.of(name + "@example.com", name, 1L, UserRole.MEMBER, Instant.now()));
        agents.save(Agent.of(
                name,
                name,
                name,
                "http://agent-runtime.test/p/" + name,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                user.id(),
                Instant.now()));
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    /** 대화를 만들고 바뀐 시각을 정한다. 같은 시각을 주면 id 가 순서를 정한다. */
    private Conversation started(CurrentUser user, long secondsAfterBase) {
        Conversation made = chat.startEmpty(user, user.displayName());
        conversationWriter.touchSession(made.id(), null, BASE.plusSeconds(secondsAfterBase));
        return conversations.findById(made.id()).orElseThrow();
    }

    private static List<Long> ids(ConversationPage page) {
        return page.items().stream().map(Conversation::id).toList();
    }

    @Test
    @DisplayName("쪽을 이어 읽으면 겹치거나 빠지는 줄 없이 최근 것부터 모두 나온다")
    void walksEveryConversationOnceInRecencyOrder() {
        CurrentUser dad = member("paging-dad");
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            expected.addFirst(started(dad, i).id());
        }

        List<Long> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            ConversationPage page = chat.conversationsOf(dad, cursor, 3);
            seen.addAll(ids(page));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);

        assertThat(seen).containsExactlyElementsOf(expected);
        assertThat(pages).isEqualTo(3);
    }

    @Test
    @DisplayName("바뀐 시각이 같으면 id 가 큰 줄이 앞에 오고 쪽 경계에서도 빠지지 않는다")
    void sameUpdatedAtIsOrderedByIdAcrossPageBoundary() {
        CurrentUser dad = member("paging-dad");
        Long first = started(dad, 10).id();
        Long second = started(dad, 10).id();
        Long third = started(dad, 10).id();

        ConversationPage head = chat.conversationsOf(dad, null, 2);
        ConversationPage tail = chat.conversationsOf(dad, head.nextCursor(), 2);

        assertThat(ids(head)).containsExactly(third, second);
        assertThat(ids(tail)).containsExactly(first);
        assertThat(tail.nextCursor()).isNull();
    }

    @Test
    @DisplayName("딱 한 쪽만큼이면 다음 쪽이 없다고 알린다")
    void exactlyOnePageHasNoNextCursor() {
        CurrentUser dad = member("paging-dad");
        started(dad, 1);
        started(dad, 2);

        assertThat(chat.conversationsOf(dad, null, 2).nextCursor()).isNull();
    }

    @Test
    @DisplayName("지운 대화와 남의 대화는 쪽에 나오지 않는다")
    void excludesDeletedAndOthersConversations() {
        CurrentUser dad = member("paging-dad");
        CurrentUser kid = member("paging-kid");
        Long kept = started(dad, 1).id();
        Long deleted = started(dad, 2).id();
        started(kid, 3);
        chat.delete(dad, deleted);

        assertThat(ids(chat.conversationsOf(dad, null, 10))).containsExactly(kept);
    }

    @Test
    @DisplayName("한 쪽의 크기는 상한을 넘지 않고 1 보다 작아지지 않는다")
    void limitIsClamped() {
        CurrentUser dad = member("paging-dad");
        for (int i = 0; i < ChatService.MAX_CONVERSATION_PAGE + 2; i++) {
            started(dad, i);
        }

        assertThat(chat.conversationsOf(dad, null, 100_000).items()).hasSize(ChatService.MAX_CONVERSATION_PAGE);
        assertThat(chat.conversationsOf(dad, null, 0).items()).hasSize(1);
    }

    @Test
    @DisplayName("읽을 수 없는 cursor 는 VALIDATION_FAILED 다")
    void rejectsMalformedCursor() {
        CurrentUser dad = member("paging-dad");

        assertThatThrownBy(() -> chat.conversationsOf(dad, "not-a-cursor", 10))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
