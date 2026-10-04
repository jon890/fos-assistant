package com.bifos.assistant.notification.domain;

import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.domain.type.NotificationTargetType;
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
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * 사용자에게 대화 밖에서 알리는 줄 하나다(ADR-070).
 *
 * <p>칸의 뜻은 {@code docs/backend/schema/notification.md} 가 갖는다. 받는 사람은 번호로만 둔다. 갈 곳은 지워져도 이 줄을
 * 남기므로 갈 곳에는 외래 키를 걸지 않는다.
 */
@Entity
@Table(
        name = "notification",
        uniqueConstraints = @UniqueConstraint(name = "uk_notification_public_id", columnNames = "public_id"))
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    /** {@code title} 칸의 길이다. 넘으면 잘라 저장한다. */
    public static final int TITLE_MAX = 200;

    /** {@code body} 칸의 길이다. 넘으면 잘라 저장한다. */
    public static final int BODY_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 화면과 API 가 쓰는 알림 번호다. 대화의 공개 식별자와 같은 방식이다(ADR-025).
     *
     * <p>넣을 때 Hibernate 가 v7 을 채운다. {@code BINARY} 로 못 박아 테스트의 H2 와 운영의 MySQL 이 같은 바이트
     * 16개로 저장한다.
     */
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "public_id", nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    private UUID publicId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    private NotificationKind kind;

    @Column(name = "title", nullable = false, length = TITLE_MAX)
    private String title;

    /** 빈 글을 받는다. 도구 인자 원문, 비밀값, 모델 답 전문을 넣지 않는다. */
    @Column(name = "body", nullable = false, length = BODY_MAX)
    private String body;

    /** 갈 곳이 없으면 비운다. {@code targetPublicId} 와 함께 채우거나 함께 비운다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", length = 20)
    private NotificationTargetType targetType;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "target_public_id", columnDefinition = "BINARY(16)")
    private UUID targetPublicId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 읽음으로 표시한 시각이다. 비면 읽지 않았다. */
    @Column(name = "read_at")
    private Instant readAt;

    /**
     * 읽지 않은 알림 한 줄이다. 제목과 본문은 칸 길이를 넘으면 잘라 둔다.
     *
     * @param target 누르면 갈 곳. 갈 곳이 없으면 null
     */
    public static Notification of(
            Long userId, NotificationKind kind, String title, String body, NotificationTarget target, Instant now) {
        Notification notification = new Notification();
        notification.userId = Objects.requireNonNull(userId, "userId");
        notification.kind = Objects.requireNonNull(kind, "kind");
        notification.title = clip(Objects.requireNonNull(title, "title"), TITLE_MAX);
        notification.body = clip(Objects.requireNonNull(body, "body"), BODY_MAX);
        if (target != null) {
            notification.targetType = target.type();
            notification.targetPublicId = target.publicId();
        }
        notification.createdAt = Objects.requireNonNull(now, "now");
        return notification;
    }

    /** 읽음으로 표시한다. 이미 읽은 줄은 처음 읽은 시각을 그대로 둔다. */
    public void markRead(Instant now) {
        if (readAt == null) {
            readAt = now;
        }
    }

    public boolean unread() {
        return readAt == null;
    }

    /** 칸 길이까지만 남긴다. 상한 자리에서 대리 쌍이 나뉘면 그 앞에서 자른다. */
    private static String clip(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max);
    }
}
