package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.proactive.application.FindingJudgement;
import com.bifos.assistant.proactive.application.model.AnnouncedKey;
import com.bifos.assistant.proactive.application.model.CheckResultBlock.Finding;
import com.bifos.assistant.proactive.application.model.CheckResultBlock.Next;
import com.bifos.assistant.proactive.application.model.JudgedFinding;
import com.bifos.assistant.proactive.domain.type.FindingKind;
import com.bifos.assistant.proactive.domain.type.FindingReason;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 발견을 「새로 알릴 것」 과 「참고」 로 나누는 검사의 순서와 조건을 합성 발견으로 고정한다. */
class FindingJudgementTest {

    private static final Instant START = Instant.parse("2026-10-04T01:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-04T01:03:00Z");
    private static final String URL = "https://example.com/kafka";
    private static final String TOPIC = "study:kafka-exactly-once";
    private static final String CHECKED_AT = "2026-10-04T10:01:00+09:00";

    private static Finding valid() {
        return new Finding(
                "study",
                TOPIC,
                "Kafka 정확히 한 번 처리",
                URL,
                CHECKED_AT,
                null,
                "CURRENT",
                "결제 이벤트를 다룬다",
                List.of("트랜잭션 API 를 설명한다"),
                List.of(),
                List.of(),
                new Next("ACTION", "예제를 돌려 본다"),
                null);
    }

    private static JudgedFinding judge(Finding finding) {
        return FindingJudgement.judge(finding, START, NOW, Set.of());
    }

    private static Finding with(Finding base, String sourceUrl, String checkedAt, String freshness) {
        return new Finding(
                base.area(),
                base.topicKey(),
                base.title(),
                sourceUrl,
                checkedAt,
                base.publishedAt(),
                freshness,
                base.whyItMatters(),
                base.facts(),
                base.inferences(),
                base.unknowns(),
                base.next(),
                base.changeSinceLast());
    }

    private static Finding withContent(
            Finding base, String title, String whyItMatters, List<String> facts, Next next) {
        return new Finding(
                base.area(),
                base.topicKey(),
                title,
                base.sourceUrl(),
                base.checkedAt(),
                base.publishedAt(),
                base.freshness(),
                whyItMatters,
                facts,
                base.inferences(),
                base.unknowns(),
                next,
                base.changeSinceLast());
    }

    private static Finding withTopic(Finding base, String topicKey, String sourceUrl, String changeSinceLast) {
        return new Finding(
                base.area(),
                topicKey,
                base.title(),
                sourceUrl,
                base.checkedAt(),
                base.publishedAt(),
                base.freshness(),
                base.whyItMatters(),
                base.facts(),
                base.inferences(),
                base.unknowns(),
                base.next(),
                changeSinceLast);
    }

    private static void assertReference(JudgedFinding judged, FindingReason reason) {
        assertThat(judged.kind()).isEqualTo(FindingKind.REFERENCE);
        assertThat(judged.reason()).isEqualTo(reason);
    }

    @Test
    @DisplayName("원문과 확인 시각과 근거가 갖춰진 학습 자료는 새로 알릴 것이다")
    void validStudyMaterialIsNew() {
        JudgedFinding judged = judge(valid());

        assertThat(judged.kind()).isEqualTo(FindingKind.NEW);
        assertThat(judged.reason()).isNull();
        assertThat(judged.sourceUrl()).isEqualTo(URL);
        assertThat(judged.checkedAt()).isEqualTo(Instant.parse("2026-10-04T01:01:00Z"));
    }

    @Test
    @DisplayName("원문 주소가 없으면 참고로 내리고 주소를 남기지 않는다")
    void missingSourceUrlIsNoSource() {
        JudgedFinding judged = judge(with(valid(), null, CHECKED_AT, "CURRENT"));

        assertReference(judged, FindingReason.NO_SOURCE);
        assertThat(judged.sourceUrl()).isNull();
    }

    @Test
    @DisplayName("http 나 https 가 아닌 주소와 절대 주소가 아닌 주소와 host 가 없는 주소는 원문이 아니다")
    void unsafeOrRelativeSourceUrlIsNoSource() {
        for (String url : new String[] {
            "javascript:alert(1)", "ftp://example.com/a", "/relative/path", "example.com/a", "https://", "https:///a", "http://a b"
        }) {
            JudgedFinding judged = judge(with(valid(), url, CHECKED_AT, "CURRENT"));

            assertReference(judged, FindingReason.NO_SOURCE);
            assertThat(judged.sourceUrl()).as(url).isNull();
        }
    }

    @Test
    @DisplayName("지난주에 확인했다는 시각은 이번에 확인한 것이 아니다")
    void checkedLongBeforeStartIsNotCheckedNow() {
        JudgedFinding judged = judge(with(valid(), URL, "2026-09-27T10:01:00+09:00", "CURRENT"));

        assertReference(judged, FindingReason.NOT_CHECKED_NOW);
        assertThat(judged.sourceUrl()).isEqualTo(URL);
        assertThat(judged.checkedAt()).isEqualTo(Instant.parse("2026-09-27T01:01:00Z"));
    }

    @Test
    @DisplayName("확인 시각을 읽지 못하거나 지금보다 한참 뒤면 이번에 확인한 것이 아니다")
    void unreadableOrFutureCheckedAtIsNotCheckedNow() {
        JudgedFinding unreadable = judge(with(valid(), URL, "어제 오후", "CURRENT"));
        JudgedFinding missing = judge(with(valid(), URL, null, "CURRENT"));
        JudgedFinding noZone = judge(with(valid(), URL, "2026-10-04T10:01:00", "CURRENT"));
        JudgedFinding future = judge(with(valid(), URL, "2026-10-04T10:09:00+09:00", "CURRENT"));

        assertReference(unreadable, FindingReason.NOT_CHECKED_NOW);
        assertThat(unreadable.checkedAt()).isNull();
        assertReference(missing, FindingReason.NOT_CHECKED_NOW);
        assertReference(noZone, FindingReason.NOT_CHECKED_NOW);
        assertReference(future, FindingReason.NOT_CHECKED_NOW);
    }

    @Test
    @DisplayName("확인 시각은 시작 5분 전부터 지금 5분 뒤까지 받아들인다")
    void checkedAtBoundariesAreInclusive() {
        // 시작 5분 전은 01:00Z 에서 5분을 뺀 00:55Z, 지금 5분 뒤는 01:03Z 에서 5분을 더한 01:08Z 다.
        assertThat(judge(with(valid(), URL, "2026-10-04T00:55:00Z", "CURRENT")).kind())
                .isEqualTo(FindingKind.NEW);
        assertThat(judge(with(valid(), URL, "2026-10-04T01:08:00Z", "CURRENT")).kind())
                .isEqualTo(FindingKind.NEW);
        assertReference(
                judge(with(valid(), URL, "2026-10-04T00:54:59Z", "CURRENT")), FindingReason.NOT_CHECKED_NOW);
        assertReference(
                judge(with(valid(), URL, "2026-10-04T01:08:01Z", "CURRENT")), FindingReason.NOT_CHECKED_NOW);
    }

    @Test
    @DisplayName("마감된 공고는 참고다")
    void closedPositionIsReference() {
        assertReference(judge(with(valid(), URL, CHECKED_AT, "CLOSED")), FindingReason.CLOSED);
    }

    @Test
    @DisplayName("오래된 동향은 참고다")
    void staleTrendIsReference() {
        assertReference(judge(with(valid(), URL, CHECKED_AT, "STALE")), FindingReason.STALE);
    }

    @Test
    @DisplayName("신선도를 모르거나 적지 않았거나 모르는 값이면 참고다")
    void unknownFreshnessIsReference() {
        for (String freshness : new String[] {"UNKNOWN", null, "FRESH"}) {
            assertReference(judge(with(valid(), URL, CHECKED_AT, freshness)), FindingReason.FRESHNESS_UNKNOWN);
        }
    }

    @Test
    @DisplayName("제목과 이유와 사실과 다음 행동 가운데 하나라도 빠지면 근거가 부족한 참고다")
    void missingPartsAreIncomplete() {
        Finding base = valid();
        Next next = base.next();

        assertReference(judge(withContent(base, base.title(), base.whyItMatters(), List.of(), next)), FindingReason.INCOMPLETE);
        assertReference(
                judge(withContent(base, base.title(), base.whyItMatters(), List.of(" "), next)),
                FindingReason.INCOMPLETE);
        assertReference(judge(withContent(base, null, base.whyItMatters(), base.facts(), next)), FindingReason.INCOMPLETE);
        assertReference(judge(withContent(base, base.title(), null, base.facts(), next)), FindingReason.INCOMPLETE);
        assertReference(judge(withContent(base, base.title(), base.whyItMatters(), base.facts(), null)), FindingReason.INCOMPLETE);
        assertReference(
                judge(withContent(base, base.title(), base.whyItMatters(), base.facts(), new Next("ACTION", null))),
                FindingReason.INCOMPLETE);
    }

    @Test
    @DisplayName("검사는 순서대로 하고 처음 걸린 까닭 하나만 남긴다")
    void firstMatchingReasonWins() {
        Finding closedAndIncomplete =
                withContent(with(valid(), URL, CHECKED_AT, "CLOSED"), null, null, List.of(), null);
        Finding noSourceAndClosed = with(valid(), "javascript:alert(1)", CHECKED_AT, "CLOSED");

        assertReference(judge(closedAndIncomplete), FindingReason.CLOSED);
        assertReference(judge(noSourceAndClosed), FindingReason.NO_SOURCE);
    }

    @Test
    @DisplayName("이미 알린 주제 키와 원문 주소가 같으면 되풀이라서 참고다")
    void sameTopicKeyAndSourceUrlIsRepeated() {
        Set<AnnouncedKey> announced = Set.of(new AnnouncedKey(TOPIC, URL));

        JudgedFinding judged = FindingJudgement.judge(valid(), START, NOW, announced);

        assertReference(judged, FindingReason.REPEATED);
    }

    @Test
    @DisplayName("같은 주제 키라도 원문 주소가 새로우면 새로 알릴 것이다")
    void sameTopicKeyWithNewSourceUrlIsNew() {
        Set<AnnouncedKey> announced = Set.of(new AnnouncedKey(TOPIC, "https://example.com/old"));

        JudgedFinding judged = FindingJudgement.judge(valid(), START, NOW, announced);

        assertThat(judged.kind()).isEqualTo(FindingKind.NEW);
    }

    @Test
    @DisplayName("같은 주제 키와 원문 주소라도 지난번과 달라진 점이 있으면 새로 알릴 것이다")
    void repeatedWithChangeSinceLastIsNew() {
        Set<AnnouncedKey> announced = Set.of(new AnnouncedKey(TOPIC, URL));
        Finding changed = withTopic(valid(), TOPIC, URL, "마감이 사흘 남았다");

        JudgedFinding judged = FindingJudgement.judge(changed, START, NOW, announced);

        assertThat(judged.kind()).isEqualTo(FindingKind.NEW);
    }

    @Test
    @DisplayName("주제 키가 비면 같은 원문 주소를 이미 알렸어도 되풀이로 보지 않는다")
    void blankTopicKeyIsNeverRepeated() {
        Set<AnnouncedKey> announced = Set.of(new AnnouncedKey(TOPIC, URL), new AnnouncedKey("", URL));

        for (String topicKey : new String[] {null, "", "  "}) {
            JudgedFinding judged =
                    FindingJudgement.judge(withTopic(valid(), topicKey, URL, null), START, NOW, announced);

            assertThat(judged.kind()).as("topicKey=%s", topicKey).isEqualTo(FindingKind.NEW);
        }
    }

    @Test
    @DisplayName("원문 주소 검사에서 먼저 걸리면 이미 알렸더라도 되풀이가 아니라 원문 없음이다")
    void noSourceComesBeforeRepeated() {
        Set<AnnouncedKey> announced = Set.of(new AnnouncedKey(TOPIC, "ftp://example.com/a"));
        Finding ftp = with(valid(), "ftp://example.com/a", CHECKED_AT, "CURRENT");

        JudgedFinding judged = FindingJudgement.judge(ftp, START, NOW, announced);

        assertReference(judged, FindingReason.NO_SOURCE);
    }
}
