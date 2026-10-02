package com.bifos.assistant.skill.domain;

import com.bifos.assistant.skill.domain.type.SkillUseSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 실행 하나에서 스킬 하나가 쓰인 것을 적은 한 줄이다. 스킬 호출 이력의 원천이다.
 *
 * <p>사용자, 에이전트, 대화는 {@code agent_execution} 과 이어 얻는다. 같은 값을 여기 다시 적지 않는다.
 * 스킬을 지워도 이 줄은 이름으로 남는다. 근거는 ADR-034 에 있다.
 *
 * <p>한 실행에서 모델이 같은 스킬을 여러 번 읽어도 한 줄이다. 유일 제약을 엔티티에도 선언한다. 테스트
 * 스키마는 이 엔티티로 만들어지므로, 여기 없으면 중복을 막는 검사가 테스트에서 통과하고 운영에서만
 * 다르게 돈다.
 */
@Entity
@Table(
        name = "execution_skill_use",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_execution_skill_use_execution_skill_source",
                        columnNames = {"execution_id", "skill_name", "source"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Accessors(fluent = true)
public class ExecutionSkillUse {

    /** 스킬 이름 칸의 길이다. 이름 규칙의 상한과 같다. */
    public static final int NAME_MAX_LENGTH = 64;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Getter
    private Long id;

    @Column(name = "execution_id", nullable = false)
    @Getter
    private Long executionId;

    @Column(name = "skill_name", nullable = false, length = NAME_MAX_LENGTH)
    @Getter
    private String skillName;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    @Getter
    private SkillUseSource source;

    @Column(name = "occurred_at", nullable = false)
    @Getter
    private Instant occurredAt;

    private ExecutionSkillUse(Long executionId, String skillName, SkillUseSource source, Instant occurredAt) {
        this.executionId = executionId;
        this.skillName = skillName;
        this.source = source;
        this.occurredAt = occurredAt;
    }

    public static ExecutionSkillUse of(Long executionId, String skillName, SkillUseSource source, Instant occurredAt) {
        return new ExecutionSkillUse(executionId, skillName, source, occurredAt);
    }
}
