package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.execution.application.ExecutionQuestions;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 실행 줄의 질문 메시지 칸을 조건부로 채우고 읽는지 확인한다. */
@BackendIntegrationTest
class ExecutionQuestionsTest {

    @Autowired
    ExecutionQuestions questions;

    @Autowired
    AgentExecutionRepository executions;


    @Test
    @DisplayName("질문은 비어 있을 때 한 번만 이어지고 이은 실행의 번호를 읽는다")
    void linksQuestionOnlyOnce() {
        AgentExecution execution = execution();

        assertThat(questions.messageIdOf(execution.id())).isEmpty();
        assertThat(questions.link(execution.id(), 11L)).isTrue();
        assertThat(questions.link(execution.id(), 22L)).isFalse();
        assertThat(questions.messageIdOf(execution.id())).contains(11L);
    }


    @Test
    @DisplayName("엔티티를 다시 저장해도 이은 질문 번호를 옛 값으로 덮지 않는다")
    void entitySaveKeepsLinkedQuestion() {
        AgentExecution execution = execution();
        questions.link(execution.id(), 11L);

        execution.recordOutput("답");
        executions.saveAndFlush(execution);

        assertThat(questions.messageIdOf(execution.id())).contains(11L);
    }


    @Test
    @DisplayName("질문이 없는 Hermes 루트 실행이 있는 대화만 걸리고 이어 두면 풀린다")
    void detectsRunWithoutQuestion() {
        long conversationId = 900_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000L);
        AgentExecution execution = executions.save(AgentExecution.builder()
            .userId(1L)
            .conversationId(conversationId)
            .profileName("question-test")
            .hermesRunId("run-" + UUID.randomUUID())
            .costMode(CostMode.SUBSCRIPTION)
            .status(ExecutionStatus.SUCCEEDED)
            .startedAt(Instant.now())
            .build());

        assertThat(questions.hasRunWithoutQuestion(conversationId)).isTrue();
        questions.link(execution.id(), 11L);
        assertThat(questions.hasRunWithoutQuestion(conversationId)).isFalse();
    }


    private AgentExecution execution() {
        return executions.save(AgentExecution.builder()
            .userId(1L)
            .profileName("question-test")
            .costMode(CostMode.SUBSCRIPTION)
            .status(ExecutionStatus.SUCCEEDED)
            .startedAt(Instant.now())
            .build());
    }
}
