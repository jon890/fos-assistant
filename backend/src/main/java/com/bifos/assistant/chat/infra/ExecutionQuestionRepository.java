package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.ExecutionQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExecutionQuestionRepository extends JpaRepository<ExecutionQuestion, Long> {

    /**
     * 그 대화에 사람의 질문 없이 Hermes 로 보낸 루트 실행이 있는가(ADR-091).
     *
     * <p>맡긴 일의 결과를 전하는 turn, 예약 작업 turn, 먼저 살펴보기 turn 이 여기 걸린다. 그 입력에 바깥 글이 실려 같은
     * session 의 이력으로 남는다. 이 표가 생기기 전의 실행은 보지 않는다. 그 대화의 첫 질문 줄보다 앞선 실행은 센다고 해도
     * 질문 줄이 없어 늘 걸리기 때문이다.
     */
    @Query("""
            select case when count(e) > 0 then true else false end from AgentExecution e
            where e.conversationId = :conversationId
                and e.parentExecutionId is null
                and e.hermesRunId is not null
                and e.id >= (select min(q.executionId) from ExecutionQuestion q, AgentExecution asked
                    where asked.id = q.executionId and asked.conversationId = :conversationId)
                and not exists (select q2.executionId from ExecutionQuestion q2 where q2.executionId = e.id)
            """)
    boolean existsRunWithoutQuestion(@Param("conversationId") Long conversationId);
}
