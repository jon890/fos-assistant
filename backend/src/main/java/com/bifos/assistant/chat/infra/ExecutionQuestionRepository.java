package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.ExecutionQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExecutionQuestionRepository extends JpaRepository<ExecutionQuestion, Long> {

    /**
     * 그 대화에 사람의 질문 없이 Hermes 로 보낸 루트 실행이 있는가(ADR-20261007 / memory-remember).
     *
     * <p>맡긴 일의 결과를 전하는 turn, 예약 작업 turn, 먼저 살펴보기 turn 이 여기 걸린다. 그 입력에 바깥 글이 실려 같은
     * session 의 이력으로 남는다. 이 표가 생기기 전의 실행과 질문 줄을 남기지 못한 실행도 센다. 그 입력에 무엇이 실렸는지
     * 기록으로 가릴 수 없다(ADR-20261008 / memory-remember-guard).
     */
    @Query("""
            select case when count(e) > 0 then true else false end from AgentExecution e
            where e.conversationId = :conversationId
                and e.parentExecutionId is null
                and e.hermesRunId is not null
                and not exists (select q2.executionId from ExecutionQuestion q2 where q2.executionId = e.id)
            """)
    boolean existsRunWithoutQuestion(@Param("conversationId") Long conversationId);
}
