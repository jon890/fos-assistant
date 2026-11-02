package com.bifos.assistant.usage.execution.infra;

import com.bifos.assistant.usage.execution.domain.AgentExecution;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 실행 줄의 {@code question_message_id} 만 다루는 저장소다(ADR-20261102 / table-reduce-r2).
 *
 * <p>엔티티는 이 칸을 {@code updatable = false} 로만 매핑해 엔티티 저장이 덮지 못하고, 아래 조건부 UPDATE 만 채우고 비운다.
 * 읽기도 영속성 컨텍스트에 남은 옛 값을 피하려고 여기 쿼리로 한다. {@code AgentExecution} 과 그 저장소가 파일 길이 상한에
 * 가까워 쿼리는 이 저장소에 두고 네이티브 SQL 로 쓴다.
 */
public interface ExecutionQuestionRepository extends Repository<AgentExecution, Long> {

    /**
     * 이 실행의 질문 메시지를 적는다. 이미 적혀 있으면 바꾸지 않고 0 을 돌려준다.
     *
     * <p>트랜잭션은 {@code ExecutionQuestions} 가 연다.
     */
    @Modifying
    @Query(
        value = "update agent_execution set question_message_id = :messageId where id = :id and question_message_id is null",
        nativeQuery = true)
    int link(@Param("id") Long id, @Param("messageId") Long messageId);


    /** 그 실행의 질문 메시지 번호만 읽는다. 질문이 없는 실행이나 없는 실행은 비어 있다. */
    @Query(value = "select question_message_id from agent_execution where id = :id", nativeQuery = true)
    Optional<Long> findMessageId(@Param("id") Long id);


    /**
     * 그 대화에 사람의 질문 없이 Hermes 로 보낸 루트 실행이 몇 개인가(ADR-20261007 / memory-remember).
     *
     * <p>맡긴 일의 결과를 전하는 turn, 예약 작업 turn, 먼저 살펴보기 turn 이 여기 걸린다. 그 입력에 바깥 글이 실려 같은
     * session 의 이력으로 남는다. 질문을 남기지 못한 실행과 질문 칸이 생기기 전의 실행도 센다. 그 입력에 무엇이 실렸는지
     * 기록으로 가릴 수 없다(ADR-20261008 / memory-remember-guard).
     */
    @Query(
        value = """
                select count(*) from agent_execution
                 where conversation_id = :conversationId
                   and parent_execution_id is null
                   and hermes_run_id is not null
                   and question_message_id is null
                """,
        nativeQuery = true)
    long countRunsWithoutQuestion(@Param("conversationId") Long conversationId);


    /** 그 대화 실행의 질문 메시지 번호를 비운다. 트랜잭션은 {@code ConversationExecutionPurge} 가 연다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """
                update agent_execution set question_message_id = null
                 where conversation_id = :conversationId and question_message_id is not null
                """,
        nativeQuery = true)
    int clearOf(@Param("conversationId") Long conversationId);
}
