package com.bifos.assistant.skill.infra;

import com.bifos.assistant.skill.domain.ExecutionSkillUse;
import com.bifos.assistant.skill.domain.SkillUseCount;
import com.bifos.assistant.skill.domain.SkillUseOccurrence;
import com.bifos.assistant.skill.domain.type.SkillUseSource;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExecutionSkillUseRepository extends JpaRepository<ExecutionSkillUse, Long> {

    /** {@code (execution_id, skill_name, source)} 유일 제약을 탄다. */
    boolean existsByExecutionIdAndSkillNameAndSource(Long executionId, String skillName, SkillUseSource source);

    /** 여러 실행의 사용을 한 번에 읽는다. 목록이 실행마다 질의하지 않게 한다. */
    List<ExecutionSkillUse> findByExecutionIdInOrderByExecutionIdAscSkillNameAsc(Collection<Long> executionIds);

    /**
     * 그 에이전트의 실행 전체에서 스킬 이름별로 묶는다. 출처는 가리지 않는다.
     *
     * <p>호출 횟수는 실행 수다. 커맨드로 부른 실행에서 모델도 그 스킬을 읽으면 {@code COMMAND} 와
     * {@code MODEL} 두 줄이 생기지만 한 번으로 센다.
     *
     * <p>줄을 다 읽어 와서 세지 않는다. 에이전트 하나의 사용은 그 에이전트를 쓴 모든 사람의 모든 turn 에
     * 걸쳐 쌓인다.
     */
    @Query("""
            select new com.bifos.assistant.skill.domain.SkillUseCount(
                u.skillName, count(distinct u.executionId), max(u.occurredAt))
            from ExecutionSkillUse u
                join AgentExecution e on e.id = u.executionId
            where e.agentId = :agentId
            group by u.skillName
            """)
    List<SkillUseCount> countByAgent(@Param("agentId") Long agentId);

    /** 그 사용자의 실행에서 쓰인 것을 실행과 에이전트와 대화 번호를 붙여 읽는다. */
    @Query("""
            select new com.bifos.assistant.skill.domain.SkillUseOccurrence(
                u.executionId, e.agentId, e.conversationId, u.skillName, u.occurredAt)
            from ExecutionSkillUse u
                join AgentExecution e on e.id = u.executionId
            where e.userId = :userId
            """)
    List<SkillUseOccurrence> findOccurrencesByUser(@Param("userId") Long userId);
}
