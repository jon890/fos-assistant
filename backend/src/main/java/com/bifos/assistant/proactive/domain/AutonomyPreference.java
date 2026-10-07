package com.bifos.assistant.proactive.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 사용자의 자동 실행 동의다. 줄이 없으면 모두 꺼짐이다. 다른 종류의 자동 실행을 열 때는 칸을 더하고, 한 칸이 여러 허락을 뜻하지 않게
 * 한다.
 */
@Entity
@Table(name = "user_autonomy_preference")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Accessors(fluent = true)
public class AutonomyPreference {

    @Id
    @Column(name = "user_id")
    private Long userId;

    /** 읽기 전용 살펴보기를 사람 없이 시작해도 된다. */
    @Column(name = "read_only_execution", nullable = false)
    private boolean readOnlyExecution;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static AutonomyPreference of(Long userId, boolean readOnlyExecution, Instant now) {
        AutonomyPreference row = new AutonomyPreference();
        row.userId = userId;
        row.readOnlyExecution = readOnlyExecution;
        row.updatedAt = now;
        return row;
    }

    public void change(boolean readOnlyExecution, Instant now) {
        this.readOnlyExecution = readOnlyExecution;
        this.updatedAt = now;
    }
}
