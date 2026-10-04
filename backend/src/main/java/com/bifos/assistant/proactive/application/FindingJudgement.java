package com.bifos.assistant.proactive.application;

import com.bifos.assistant.proactive.application.model.AnnouncedKey;
import com.bifos.assistant.proactive.application.model.CheckResultBlock.Finding;
import com.bifos.assistant.proactive.application.model.JudgedFinding;
import com.bifos.assistant.proactive.domain.type.FindingKind;
import com.bifos.assistant.proactive.domain.type.FindingReason;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 발견마다 「새로 알릴 것」 과 「참고」 를 정한다. 모델이 쓴 값을 믿지 않고 Control Plane 이 확인한다.
 *
 * <p>검사는 아래 순서이고 처음 걸린 까닭 하나만 남긴다. 순서와 조건은 {@code docs/backend/proactive-check.md} 의
 * 「검사」 가 갖는다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class FindingJudgement {

    /** 확인 시각이 살펴보기 시작보다 이르거나 지금보다 늦어도 받아들이는 어긋남이다. 시계 차이를 견디기 위한 값이다. */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    private static final String CLOSED = "CLOSED";
    private static final String STALE = "STALE";
    private static final String CURRENT = "CURRENT";

    /**
     * 발견 하나를 검사한다.
     *
     * @param checkStartedAt 이번 살펴보기를 시작한 시각이다
     * @param now 지금이다
     * @param alreadyAnnounced 같은 점검 대화에서 최근에 이미 알린 주제 키와 원문 주소다
     */
    public static JudgedFinding judge(
            Finding finding, Instant checkStartedAt, Instant now, Set<AnnouncedKey> alreadyAnnounced) {
        String sourceUrl = isHttpAbsolute(finding.sourceUrl()) ? finding.sourceUrl() : null;
        Instant checkedAt = parseInstant(finding.checkedAt());
        FindingReason reason = reasonOf(finding, sourceUrl, checkedAt, checkStartedAt, now, alreadyAnnounced);
        FindingKind kind = reason == null ? FindingKind.NEW : FindingKind.REFERENCE;
        return new JudgedFinding(finding, kind, reason, sourceUrl, checkedAt);
    }

    /** 걸린 까닭을 순서대로 찾는다. 모두 통과하면 {@code null} 이다. */
    private static FindingReason reasonOf(
            Finding finding,
            String sourceUrl,
            Instant checkedAt,
            Instant checkStartedAt,
            Instant now,
            Set<AnnouncedKey> alreadyAnnounced) {
        if (sourceUrl == null) {
            return FindingReason.NO_SOURCE;
        }
        if (checkedAt == null
                || checkedAt.isBefore(checkStartedAt.minus(CLOCK_SKEW))
                || checkedAt.isAfter(now.plus(CLOCK_SKEW))) {
            return FindingReason.NOT_CHECKED_NOW;
        }
        if (CLOSED.equals(finding.freshness())) {
            return FindingReason.CLOSED;
        }
        if (STALE.equals(finding.freshness())) {
            return FindingReason.STALE;
        }
        if (!CURRENT.equals(finding.freshness())) {
            return FindingReason.FRESHNESS_UNKNOWN;
        }
        if (isIncomplete(finding)) {
            return FindingReason.INCOMPLETE;
        }
        if (isRepeated(finding, sourceUrl, alreadyAnnounced)) {
            return FindingReason.REPEATED;
        }
        return null;
    }

    private static boolean isIncomplete(Finding finding) {
        return isBlank(finding.title())
                || isBlank(finding.whyItMatters())
                || !hasText(finding.facts())
                || finding.next() == null
                || isBlank(finding.next().text());
    }

    /** 주제 키가 있고 같은 주제 키와 원문 주소를 이미 알렸으며, 지난번과 달라진 점도 없을 때다. */
    private static boolean isRepeated(Finding finding, String sourceUrl, Set<AnnouncedKey> alreadyAnnounced) {
        return !isBlank(finding.topicKey())
                && isBlank(finding.changeSinceLast())
                && alreadyAnnounced.contains(new AnnouncedKey(finding.topicKey(), sourceUrl));
    }

    /** {@code http} 나 {@code https} 의 절대 주소이고 host 가 있다. */
    private static boolean isHttpAbsolute(String value) {
        if (isBlank(value)) {
            return false;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            return uri.isAbsolute()
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null;
        } catch (URISyntaxException ex) {
            return false;
        }
    }

    private static Instant parseInstant(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static boolean hasText(List<String> values) {
        return values != null && values.stream().anyMatch(value -> !isBlank(value));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
