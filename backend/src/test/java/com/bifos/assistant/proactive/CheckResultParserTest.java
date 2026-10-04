package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.proactive.application.CheckResultParser;
import com.bifos.assistant.proactive.application.model.CheckResultBlock;
import com.bifos.assistant.proactive.application.model.CheckResultBlock.Finding;
import com.bifos.assistant.proactive.domain.type.CheckOutcome;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 답 끝의 결과 블록을 읽는 규칙을 합성 답으로 고정한다. */
class CheckResultParserTest {

    private static final String FINDING = """
            {
              "area": "study",
              "topicKey": "study:kafka-exactly-once",
              "title": "Kafka 정확히 한 번 처리",
              "sourceUrl": "https://example.com/kafka",
              "checkedAt": "2026-10-04T10:01:00+09:00",
              "publishedAt": "2026-09-30",
              "freshness": "CURRENT",
              "whyItMatters": "결제 이벤트를 다룬다",
              "facts": ["트랜잭션 API 를 설명한다"],
              "inferences": ["멱등 프로듀서와 함께 읽어야 한다"],
              "unknowns": ["브로커 버전 조건"],
              "next": {"type": "ACTION", "text": "예제를 직접 돌려 본다"},
              "changeSinceLast": "새 원문이 나왔다"
            }""";

    private static final String VALID_JSON = """
            {
              "version": 1,
              "outcome": "FINDINGS",
              "summary": "한 건을 찾았어요",
              "findings": [%s],
              "questions": ["다음 주에는 무엇을 볼까요"],
              "followUpCandidates": ["예제 실습"],
              "sourceFailures": ["blog.example: 접속 실패"]
            }""".formatted(FINDING);

    private final CheckResultParser parser = new CheckResultParser();

    private static String block(String json) {
        return CheckResultParser.OPEN_TAG + "\n" + json + "\n" + CheckResultParser.CLOSE_TAG;
    }

    @Test
    @DisplayName("답 끝의 정상 블록을 모든 칸까지 읽는다")
    void parsesValidBlock() {
        CheckResultBlock result = parser.parse("살펴봤어요.\n\n" + block(VALID_JSON)).orElseThrow();

        assertThat(result.version()).isEqualTo(1);
        assertThat(result.outcome()).isEqualTo(CheckOutcome.FINDINGS);
        assertThat(result.summary()).isEqualTo("한 건을 찾았어요");
        assertThat(result.questions()).containsExactly("다음 주에는 무엇을 볼까요");
        assertThat(result.followUpCandidates()).containsExactly("예제 실습");
        assertThat(result.sourceFailures()).containsExactly("blog.example: 접속 실패");
        assertThat(result.findings()).hasSize(1);
        Finding finding = result.findings().get(0);
        assertThat(finding.area()).isEqualTo("study");
        assertThat(finding.topicKey()).isEqualTo("study:kafka-exactly-once");
        assertThat(finding.title()).isEqualTo("Kafka 정확히 한 번 처리");
        assertThat(finding.sourceUrl()).isEqualTo("https://example.com/kafka");
        assertThat(finding.checkedAt()).isEqualTo("2026-10-04T10:01:00+09:00");
        assertThat(finding.publishedAt()).isEqualTo("2026-09-30");
        assertThat(finding.freshness()).isEqualTo("CURRENT");
        assertThat(finding.whyItMatters()).isEqualTo("결제 이벤트를 다룬다");
        assertThat(finding.facts()).containsExactly("트랜잭션 API 를 설명한다");
        assertThat(finding.inferences()).containsExactly("멱등 프로듀서와 함께 읽어야 한다");
        assertThat(finding.unknowns()).containsExactly("브로커 버전 조건");
        assertThat(finding.next().type()).isEqualTo("ACTION");
        assertThat(finding.next().text()).isEqualTo("예제를 직접 돌려 본다");
        assertThat(finding.changeSinceLast()).isEqualTo("새 원문이 나왔다");
    }

    @Test
    @DisplayName("Markdown 코드 울타리로 감싼 JSON 도 읽는다")
    void parsesBlockWrappedInCodeFence() {
        String answer = block("```json\n" + VALID_JSON + "\n```");

        assertThat(parser.parse(answer)).isPresent();
    }

    @Test
    @DisplayName("블록이 둘이면 마지막 것을 읽는다")
    void readsLastBlockWhenThereAreTwo() {
        String first = block("{\"version\": 1, \"outcome\": \"NOTHING_NEW\"}");
        String answer = first + "\n중간 글\n" + block(VALID_JSON);

        CheckResultBlock result = parser.parse(answer).orElseThrow();

        assertThat(result.outcome()).isEqualTo(CheckOutcome.FINDINGS);
    }

    @Test
    @DisplayName("블록이 없으면 빈 값이다")
    void returnsEmptyWithoutBlock() {
        assertThat(parser.parse("블록 없이 끝난 답")).isEmpty();
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse(CheckResultParser.OPEN_TAG + VALID_JSON)).isEmpty();
    }

    @Test
    @DisplayName("JSON 이 아니면 예외 없이 빈 값이다")
    void returnsEmptyWhenNotJson() {
        assertThat(parser.parse(block("이건 JSON 이 아니에요"))).isEmpty();
        assertThat(parser.parse(block("{\"version\": 1, \"outcome\": "))).isEmpty();
        assertThat(parser.parse(block("[1, 2]"))).isEmpty();
        assertThat(parser.parse(block(""))).isEmpty();
    }

    @Test
    @DisplayName("version 이 1 이 아니면 읽지 못한 것이다")
    void returnsEmptyWhenVersionIsNotOne() {
        assertThat(parser.parse(block("{\"version\": 2, \"outcome\": \"NOTHING_NEW\"}")))
                .isEmpty();
        assertThat(parser.parse(block("{\"version\": \"1\", \"outcome\": \"NOTHING_NEW\"}")))
                .isEmpty();
        assertThat(parser.parse(block("{\"outcome\": \"NOTHING_NEW\"}"))).isEmpty();
    }

    @Test
    @DisplayName("outcome 이 없거나 모르는 값이면 읽지 못한 것이다")
    void returnsEmptyWhenOutcomeIsMissingOrUnknown() {
        assertThat(parser.parse(block("{\"version\": 1}"))).isEmpty();
        assertThat(parser.parse(block("{\"version\": 1, \"outcome\": \"MAYBE\"}")))
                .isEmpty();
        assertThat(parser.parse(block("{\"version\": 1, \"outcome\": \"INVALID_RESULT\"}")))
                .isEmpty();
    }

    @Test
    @DisplayName("NOTHING_NEW 는 칸이 모두 비어도 읽는다")
    void parsesNothingNewWithoutOtherFields() {
        CheckResultBlock result = parser.parse(block("{\"version\": 1, \"outcome\": \"NOTHING_NEW\"}"))
                .orElseThrow();

        assertThat(result.outcome()).isEqualTo(CheckOutcome.NOTHING_NEW);
        assertThat(result.summary()).isNull();
        assertThat(result.findings()).isEmpty();
        assertThat(result.questions()).isEmpty();
    }

    @Test
    @DisplayName("상한을 넘는 글은 자르고 넘는 배열 원소는 버린다")
    void clipsLongTextAndDropsExtraElements() {
        String json = """
                {
                  "version": 1,
                  "outcome": "FINDINGS",
                  "summary": "%s",
                  "findings": [%s],
                  "questions": ["%s", "q2", "q3", "q4"],
                  "followUpCandidates": ["f1", "f2", "f3", "f4"],
                  "sourceFailures": ["s1", "s2", "s3", "s4", "s5", "s6"]
                }""".formatted(
                        "가".repeat(301),
                        String.join(",", longFinding(), FINDING, FINDING, FINDING, FINDING, FINDING, FINDING, FINDING),
                        "나".repeat(301));

        CheckResultBlock result = parser.parse(block(json)).orElseThrow();

        assertThat(result.summary()).hasSize(300);
        assertThat(result.findings()).hasSize(5);
        assertThat(result.questions()).hasSize(3);
        assertThat(result.questions().get(0)).hasSize(300);
        assertThat(result.followUpCandidates()).containsExactly("f1", "f2", "f3");
        assertThat(result.sourceFailures()).containsExactly("s1", "s2", "s3", "s4", "s5");

        Finding clipped = result.findings().get(0);
        assertThat(clipped.area()).hasSize(40);
        assertThat(clipped.topicKey()).hasSize(120);
        assertThat(clipped.title()).hasSize(120);
        assertThat(clipped.sourceUrl()).hasSize(2000);
        assertThat(clipped.whyItMatters()).hasSize(600);
        assertThat(clipped.facts()).hasSize(6);
        assertThat(clipped.facts().get(0)).hasSize(300);
        assertThat(clipped.inferences()).hasSize(6);
        assertThat(clipped.unknowns()).hasSize(6);
        assertThat(clipped.next().text()).hasSize(300);
        assertThat(clipped.changeSinceLast()).hasSize(300);
    }

    @Test
    @DisplayName("모르는 칸은 무시하고 타입이 다른 칸은 비운 채 블록은 읽는다")
    void ignoresUnknownFieldsAndWrongTypes() {
        String json = """
                {
                  "version": 1,
                  "outcome": "FINDINGS",
                  "extra": {"anything": true},
                  "summary": 7,
                  "findings": [
                    "문자열 원소",
                    {"title": "제목만 있다", "facts": "배열이 아니다", "next": "객체가 아니다", "unknown": 1}
                  ],
                  "questions": [1, "질문", ""]
                }""";

        CheckResultBlock result = parser.parse(block(json)).orElseThrow();

        assertThat(result.summary()).isNull();
        assertThat(result.questions()).containsExactly("질문");
        assertThat(result.findings()).hasSize(1);
        Finding finding = result.findings().get(0);
        assertThat(finding.title()).isEqualTo("제목만 있다");
        assertThat(finding.facts()).isEmpty();
        assertThat(finding.next()).isNull();
        assertThat(finding.sourceUrl()).isNull();
    }

    @Test
    @DisplayName("블록 앞의 공백과 뒤의 모델 글은 읽는 데 영향을 주지 않는다")
    void ignoresTextAroundBlock() {
        String answer = "앞 글\n" + block("  \n " + VALID_JSON + " \n ") + "\n뒤에 붙은 글";

        Optional<CheckResultBlock> result = parser.parse(answer);

        assertThat(result).isPresent();
    }

    private static String longFinding() {
        String longItem = "\"" + "다".repeat(301) + "\"";
        String items = String.join(",", longItem, longItem, longItem, longItem, longItem, longItem, longItem);
        return """
                {
                  "area": "%s",
                  "topicKey": "%s",
                  "title": "%s",
                  "sourceUrl": "https://example.com/%s",
                  "whyItMatters": "%s",
                  "facts": [%s],
                  "inferences": [%s],
                  "unknowns": [%s],
                  "next": {"type": "ACTION", "text": "%s"},
                  "changeSinceLast": "%s"
                }""".formatted(
                        "a".repeat(41),
                        "k".repeat(121),
                        "t".repeat(121),
                        "u".repeat(2000),
                        "w".repeat(601),
                        items,
                        items,
                        items,
                        "n".repeat(301),
                        "c".repeat(301));
    }
}
