package com.bifos.assistant.followup.domain;

import com.bifos.assistant.followup.domain.type.FollowUpStatus;
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
import java.util.Arrays;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * 사용자 한 사람의 할 일 한 줄이다(ADR-073).
 *
 * <p>칸의 뜻은 {@code backend/docs/data-schema.md} 의 「follow_up」 이 갖고, 상태 전이는 {@code docs/features/attention.md} 의
 * 「상태」 가 갖는다. 대화와 실행은 지워져도 이 줄을 남기므로 번호로만 둔다.
 *
 * <p>유일 제약을 엔티티에도 선언한다. 테스트는 엔티티로 스키마를 만들므로, 선언하지 않으면 같은 제목이 동시에 열릴 때의 처리가
 * 테스트에서 돌지 않는다.
 */
@Entity
@Table(
        name = "follow_up",
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_follow_up_public_id", columnNames = "public_id"),
            @UniqueConstraint(
                    name = "uk_follow_up_open_title",
                    columnNames = {"user_id", "title_key", "open_marker"})
        })
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FollowUp {

    /** 열린 줄의 {@code openMarker} 값이다. 끝난 줄은 비워 유일 제약에 걸리지 않게 한다. */
    public static final int OPEN_MARKER = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** API 와 화면에 쓰는 번호다. 넣을 때 Hibernate 가 v7 을 채운다. */
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "public_id", nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    private UUID publicId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 연결한 대화다. 직접 더할 때 고르지 않았으면 비어 있다. */
    @Column(name = "conversation_id")
    private Long conversationId;

    /** 제안한 실행이다. 사람이 직접 더했으면 비어 있다. */
    @Column(name = "proposed_by_execution_id")
    private Long proposedByExecutionId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** 정규화한 제목의 SHA-256 16진수다. */
    @Column(name = "title_key", nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String titleKey;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "waiting", nullable = false)
    private boolean waiting;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FollowUpStatus status;

    /** 열린 줄만 {@link #OPEN_MARKER} 이고 끝난 줄은 비어 있다. 유일 제약에만 쓴다. */
    @Column(name = "open_marker", columnDefinition = "TINYINT")
    private Integer openMarker;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 받아들이거나 직접 더한 시각이다. */
    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** 에이전트가 제안한 할 일이다. 사람이 받아들이기 전까지 {@code PROPOSED} 다. */
    public static FollowUp proposed(
            Long userId,
            Long conversationId,
            Long executionId,
            String title,
            String titleKey,
            Instant dueAt,
            boolean waiting,
            Instant now) {
        FollowUp followUp = started(userId, conversationId, title, titleKey, dueAt, waiting, now);
        followUp.proposedByExecutionId = executionId;
        followUp.status = FollowUpStatus.PROPOSED;
        return followUp;
    }

    /** 사람이 직접 더한 할 일이다. 바로 {@code OPEN} 이고 받아들인 시각이 만든 시각이다. */
    public static FollowUp opened(
            Long userId,
            Long conversationId,
            String title,
            String titleKey,
            Instant dueAt,
            boolean waiting,
            Instant now) {
        FollowUp followUp = started(userId, conversationId, title, titleKey, dueAt, waiting, now);
        followUp.status = FollowUpStatus.OPEN;
        followUp.acceptedAt = now;
        return followUp;
    }

    private static FollowUp started(
            Long userId,
            Long conversationId,
            String title,
            String titleKey,
            Instant dueAt,
            boolean waiting,
            Instant now) {
        FollowUp followUp = new FollowUp();
        followUp.userId = userId;
        followUp.conversationId = conversationId;
        followUp.title = title;
        followUp.titleKey = titleKey;
        followUp.dueAt = dueAt;
        followUp.waiting = waiting;
        followUp.openMarker = OPEN_MARKER;
        followUp.createdAt = now;
        followUp.updatedAt = now;
        return followUp;
    }

    /** 제안을 받아들였다. */
    public void accept(Instant now) {
        require(FollowUpStatus.PROPOSED);
        this.status = FollowUpStatus.OPEN;
        this.acceptedAt = now;
        this.updatedAt = now;
    }

    /** 제안을 거절했다. */
    public void reject(Instant now) {
        require(FollowUpStatus.PROPOSED);
        close(FollowUpStatus.REJECTED, now);
    }

    /** 끝냈다. */
    public void done(Instant now) {
        require(FollowUpStatus.OPEN);
        close(FollowUpStatus.DONE, now);
    }

    /** 그만두었다. */
    public void drop(Instant now) {
        require(FollowUpStatus.OPEN);
        close(FollowUpStatus.DROPPED, now);
    }

    /** 제목, 기한, 기다리는 중을 고친다. 끝나지 않은 줄만 고친다. */
    public void revise(String title, String titleKey, Instant dueAt, boolean waiting, Instant now) {
        require(FollowUpStatus.PROPOSED, FollowUpStatus.OPEN);
        this.title = title;
        this.titleKey = titleKey;
        this.dueAt = dueAt;
        this.waiting = waiting;
        this.updatedAt = now;
    }

    /** 에이전트가 제안한 줄인가. */
    public boolean proposedByAgent() {
        return proposedByExecutionId != null;
    }

    private void close(FollowUpStatus closed, Instant now) {
        this.status = closed;
        this.openMarker = null;
        this.closedAt = now;
        this.updatedAt = now;
    }

    private void require(FollowUpStatus... expected) {
        if (!Arrays.asList(expected).contains(status)) {
            throw new IllegalStateException(
                    "follow-up must be one of " + Arrays.toString(expected) + " but is " + status);
        }
    }
}
