package com.bifos.assistant.proactive.domain;

import com.bifos.assistant.proactive.domain.type.FindingKind;
import com.bifos.assistant.proactive.domain.type.FindingReason;
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
 * 살펴보기 결과 블록의 발견 하나다.
 *
 * <p>다음 살펴보기가 최근에 알린 것을 입력에 싣는 데 쓴다. 발견의 이유, 사실, 추정, 다음 행동은 대화 메시지에만 있고 여기 두지 않는다. 같은 글을
 * 두 곳에 두면 사용자가 대화를 지워도 한쪽에 남는다.
 */
@Entity
@Table(name = "proactive_check_finding")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Accessors(fluent = true)
public class ProactiveCheckFinding {

    static final int AREA_MAX_LENGTH = 40;
    static final int TOPIC_KEY_MAX_LENGTH = 120;
    static final int TITLE_MAX_LENGTH = 120;
    static final int SOURCE_URL_MAX_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "check_id", nullable = false)
    private Long checkId;

    /** 점검 대화. {@code proactive_check.conversation_id} 와 같다. 입력에 실을 발견을 대화로 읽으려고 둔다. */
    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private FindingKind kind;

    /** {@code REFERENCE} 의 까닭. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 32)
    private FindingReason reason;

    /** 분야 지침이 정한 영역. */
    @Column(name = "area", nullable = false, length = AREA_MAX_LENGTH)
    private String area;

    /** 분야 지침이 정한 주제 키. 같은 주제와 같은 원문을 다시 알리지 않는 판정에 쓴다. */
    @Column(name = "topic_key", length = TOPIC_KEY_MAX_LENGTH)
    private String topicKey;

    /** 발견의 제목. 모델이 쓴 글이다. */
    @Column(name = "title", nullable = false, length = TITLE_MAX_LENGTH)
    private String title;

    /** 검사를 통과한 원문 주소. {@code NO_SOURCE} 면 비어 있다. */
    @Column(name = "source_url", length = SOURCE_URL_MAX_LENGTH)
    private String sourceUrl;

    /** 원문을 확인한 시각. 읽지 못했으면 비어 있다. */
    @Column(name = "checked_at")
    private Instant checkedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 칸 길이를 넘는 글은 잘라 넣는다. 모델이 쓴 글이라 길이를 믿지 않는다. */
    public static ProactiveCheckFinding of(
            Long checkId,
            Long conversationId,
            FindingKind kind,
            FindingReason reason,
            String area,
            String topicKey,
            String title,
            String sourceUrl,
            Instant checkedAt,
            Instant now) {
        ProactiveCheckFinding finding = new ProactiveCheckFinding();
        finding.checkId = checkId;
        finding.conversationId = conversationId;
        finding.kind = kind;
        finding.reason = reason;
        finding.area = truncate(area, AREA_MAX_LENGTH);
        finding.topicKey = truncate(topicKey, TOPIC_KEY_MAX_LENGTH);
        finding.title = truncate(title, TITLE_MAX_LENGTH);
        finding.sourceUrl = truncate(sourceUrl, SOURCE_URL_MAX_LENGTH);
        finding.checkedAt = checkedAt;
        finding.createdAt = now;
        return finding;
    }

    /** 칸 길이까지만 남긴다. 상한 자리에서 대리 쌍이 나뉘면 그 앞에서 자른다. */
    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Character.isHighSurrogate(value.charAt(maxLength - 1)) ? maxLength - 1 : maxLength);
    }
}
