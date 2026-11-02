package com.bifos.assistant.usage.execution.application;

import com.bifos.assistant.usage.execution.infra.ExecutionQuestionRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사람이 보낸 turn 의 실행과 그 질문 메시지를 잇는다(ADR-20261007 / memory-remember, ADR-20261102 / table-reduce-r2).
 *
 * <p>잇는 값은 실행 줄의 {@code question_message_id} 하나다. 질문 메시지의 내용과 보낸 사람은 대화 쪽이 읽는다.
 */
@Service
@RequiredArgsConstructor
public class ExecutionQuestions {
    private final ExecutionQuestionRepository questions;


    /**
     * 그 실행에 질문 메시지를 잇는다. 이미 이어 두었으면 바꾸지 않는다.
     *
     * @return 이번에 이었으면 참
     */
    @Transactional
    public boolean link(Long executionId, Long messageId) {
        return questions.link(executionId, messageId) == 1;
    }


    /** 그 실행의 질문 메시지 번호. 사람이 보낸 turn 이 아니거나 이어 두지 못했으면 비어 있다. */
    @Transactional(readOnly = true)
    public Optional<Long> messageIdOf(Long executionId) {
        return questions.findMessageId(executionId);
    }


    /** 그 대화에 사람의 질문 없이 Hermes 로 보낸 루트 실행이 있는가. */
    @Transactional(readOnly = true)
    public boolean hasRunWithoutQuestion(Long conversationId) {
        return questions.countRunsWithoutQuestion(conversationId) > 0;
    }
}
