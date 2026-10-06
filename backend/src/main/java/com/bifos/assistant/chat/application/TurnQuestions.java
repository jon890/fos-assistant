package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.type.MessageRole;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ExecutionQuestionRepository;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 사람이 보낸 turn 의 실행에서 그 질문을 읽는다(ADR-091). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TurnQuestions {
    private final ExecutionQuestionRepository questions;
    private final ChatMessageRepository messages;

    /**
     * 그 실행이 사람이 보낸 turn 이면 질문 원문을 낸다.
     *
     * <p>질문이 그 사용자가 보낸 {@code USER} 메시지가 아니면 없는 것으로 본다. 줄이 어긋나도 바로 저장으로 넘어가지 않게 한다.
     *
     * @param userId 그 실행의 사용자
     */
    public Optional<String> questionOf(Long executionId, Long userId) {
        return questions
                .findById(executionId)
                .flatMap(question -> messages.findById(question.messageId()))
                .filter(message -> message.role() == MessageRole.USER)
                .filter(message -> Objects.equals(message.senderUserId(), userId))
                .map(ChatMessage::content);
    }
}
