package com.bifos.assistant.memory.domain;

import com.bifos.assistant.memory.domain.type.MemoryCaptureKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 에이전트가 {@code memory_remember} 로 남긴 기록 한 줄이다(ADR-20261007 / memory-remember).
 *
 * <p>대화의 답 아래에 「기억했어요」 와 제안 카드를 그리고, 되돌리기가 무엇을 되돌릴지 정한다. 칸의 뜻은
 * {@code docs/backend/schema/memory.md} 의 「memory_capture」 가 갖는다.
 */
@Entity
@Table(name = "memory_capture")
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemoryCapture {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "memory_id", nullable = false)
    private Long memoryId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 기록을 남긴 실행의 대화다. 대화 밖의 실행이면 null 이다. */
    @Column(name = "conversation_id")
    private Long conversationId;

    @Column(name = "execution_id", nullable = false)
    private Long executionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemoryCaptureKind kind;

    /**
     * 되돌릴 때 견주는 판 번호다. {@code CREATED} 는 저장한 때의 판, {@code UPDATED} 는 고치기 전의 판이다. {@code PROPOSED}
     * 는 null 이다.
     */
    @Column(name = "base_revision")
    private Integer baseRevision;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "undone_at")
    private Instant undoneAt;

    public static MemoryCapture of(
            Long memoryId,
            Long userId,
            Long conversationId,
            Long executionId,
            MemoryCaptureKind kind,
            Integer baseRevision,
            Instant now) {
        MemoryCapture capture = new MemoryCapture();
        capture.memoryId = memoryId;
        capture.userId = userId;
        capture.conversationId = conversationId;
        capture.executionId = executionId;
        capture.kind = kind;
        capture.baseRevision = baseRevision;
        capture.createdAt = now;
        return capture;
    }

    /** 사람이 되돌렸다. 대화에 다시 그리지 않는다. */
    public void undo(Instant at) {
        undoneAt = at;
    }

    public boolean undone() {
        return undoneAt != null;
    }
}
