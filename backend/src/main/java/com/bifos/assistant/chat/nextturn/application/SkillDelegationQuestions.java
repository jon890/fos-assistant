package com.bifos.assistant.chat.nextturn.application;

import com.bifos.assistant.chat.conversation.application.ConversationAccess;
import com.bifos.assistant.chat.conversation.application.ConversationEntry;
import com.bifos.assistant.chat.conversation.application.ConversationWriter;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.nextturn.application.dto.SkillDelegationQuestion;
import com.bifos.assistant.chat.turn.application.ChatService;
import com.bifos.assistant.chat.turn.application.ConversationEventHub;
import com.bifos.assistant.chat.turn.application.dto.ChatEvent;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 전문 에이전트의 스킬을 맡길 때 대화를 정하고 질문을 남긴다(docs/features/assistant-hub/spec.md 의 REQ10, DECISION26).
 *
 * <p>대화 turn 을 열지 않으므로 turn 잠금을 잡지 않는다. 답이 도는 동안에도 남긴다. 맡기기는 {@code orchestration} 이 하고, 이
 * 클래스는 그 앞뒤로 대화에 남는 글만 다룬다. {@code orchestration} 은 {@link ChatService} 를 부르지 못해 이 좁은 길을 둔다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillDelegationQuestions {
    /** 대화 제목의 글자 수 상한이다. 보내기의 제목과 같다. */
    private static final int TITLE_LIMIT = 60;

    private final ConversationAccess access;
    private final ConversationEntry entry;
    private final ChatService chat;
    private final ChatMessageRepository messages;
    private final ConversationRepository conversations;
    private final ConversationWriter conversationWriter;
    private final ConversationEventHub hub;
    private final TransactionTemplate transactions;
    private final Clock clock;


    /**
     * 사람이 보낸 질문을 남긴다. 대화 번호가 없으면 기본 비서와 새 대화를 만든다.
     *
     * @throws com.bifos.assistant.shared.error.ApiException 남의 대화나 지운 대화면 {@code CONVERSATION_NOT_FOUND}, 기본 비서의 대화가
     *     아니면(읽기 전용) {@code VALIDATION_FAILED}
     */
    public SkillDelegationQuestion ask(CurrentUser user, Long conversationId, String text) {
        Conversation conversation;
        if (conversationId == null) {
            conversation = chat.startEmpty(user, null);
        } else {
            entry.requireWritable(user, conversationId);
            conversation = access.requireOwn(user, conversationId);
        }
        ChatMessage question = transactions.execute(status -> {
            conversationWriter.fillTitleIfBlank(conversation, titleFrom(text));
            ChatMessage saved = messages.save(ChatMessage.fromUser(conversation.id(), user.id(), text, clock.instant()));
            // 사람이 질문했으니 자동 turn 수를 새로 세고 숨긴 대화를 다시 보인다. 보내기와 같다.
            conversationWriter.resetAutoTurns(conversation.id());
            conversationWriter.showInList(conversation.id());
            return saved;
        });
        hub.publish(conversation.id(), ChatEvent.user(conversation.publicId(), question.id(), text));
        return new SkillDelegationQuestion(conversation, question.id(), conversationId == null);
    }


    /** 예약 작업의 시작 알림 줄과 지시를 남긴다. 예약 turn 과 같은 두 줄이다. 대화는 발화가 이미 정했다. */
    public SkillDelegationQuestion askScheduled(CurrentUser user, Long conversationId, String notice, String text) {
        Conversation conversation = access.requireOwn(user, conversationId);
        List<ChatMessage> saved = transactions.execute(status -> {
            ChatMessage line = messages.save(ChatMessage.fromSystem(conversation.id(), notice, clock.instant()));
            conversationWriter.fillTitleIfBlank(conversation, titleFrom(text));
            ChatMessage question = messages.save(ChatMessage.fromUser(conversation.id(), user.id(), text, clock.instant()));
            conversationWriter.resetAutoTurns(conversation.id());
            return List.of(line, question);
        });
        hub.publish(conversation.id(), ChatEvent.system(conversation.publicId(), saved.get(0).id(), notice));
        hub.publish(conversation.id(), ChatEvent.user(conversation.publicId(), saved.get(1).id(), text));
        return new SkillDelegationQuestion(conversation, saved.get(1).id(), false);
    }


    /**
     * 맡기기를 시작하지 못했을 때 남긴 질문을 지운다. 그 질문으로 새로 만든 대화도 지운다. 지우다 실패하면 경고 로그만 남긴다. 원래 실패를
     * 가리지 않기 위해서다.
     */
    public void withdraw(SkillDelegationQuestion question) {
        try {
            transactions.executeWithoutResult(status -> {
                messages.deleteById(question.messageId());
                if (question.createdConversation()) {
                    conversations.deleteById(question.conversation().id());
                }
            });
        } catch (RuntimeException ex) {
            log.warn("맡기지 못한 스킬 맡기기의 질문을 지우지 못했다 messageId={}", question.messageId(), ex);
        }
    }


    private static String titleFrom(String text) {
        String single = text.strip().replaceAll("\\s+", " ");
        return single.length() <= TITLE_LIMIT ? single : single.substring(0, TITLE_LIMIT);
    }
}
