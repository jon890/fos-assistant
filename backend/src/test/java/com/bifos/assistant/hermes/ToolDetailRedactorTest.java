package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.usage.domain.ExecutionEvent;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ToolDetailRedactorTest {
    private static final String FIRST_ID = "12345678-1234-5678-9012-123456789abc";
    private static final String SECOND_ID = "abcdef01-1234-5678-9012-123456789abc";
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("중첩 JSON과 배열의 비밀 키는 값의 모양이나 타입과 관계없이 가린다")
    void redactsNestedSecretFieldsRegardlessOfValueShape() {
        String detail = """
                {"token":"short","nested":[{"API_KEY":"tiny","refresh_token":123,
                "private-key":{"value":"nested"},"credentials":["one","two"],
                "clientSecret":"short"}],"price":12000,"date":"2026-10-01"}
                """;

        JsonNode redacted = mapper.readTree(ToolDetailRedactor.redact(detail, false));

        assertThat(redacted.path("token").asText()).isEqualTo("[가림]");
        for (String key : new String[] {"API_KEY", "refresh_token", "private-key", "credentials", "clientSecret"}) {
            assertThat(redacted.path("nested").get(0).path(key).asText()).isEqualTo("[가림]");
        }
        assertThat(redacted.path("price").asInt()).isEqualTo(12000);
        assertThat(redacted.path("date").asText()).isEqualTo("2026-10-01");
    }

    @Test
    @DisplayName("같은 설명 안에서 UUID의 대소문자를 통일하고 다른 UUID는 구분한다")
    void replacesRepeatedUuidWithSameLabelAndDifferentUuidWithDifferentLabel() {
        String detail = FIRST_ID + " " + FIRST_ID.toUpperCase() + " " + SECOND_ID;

        assertThat(ToolDetailRedactor.redact(detail, false)).isEqualTo("[항목 1] [항목 1] [항목 2]");
        assertThat(ToolDetailRedactor.redact(SECOND_ID, false)).isEqualTo("[항목 1]");
    }

    @Test
    @DisplayName("Bearer와 JWT와 알려진 토큰 접두를 일반 문장 안에서도 가린다")
    void redactsTokensInProse() {
        String detail = "Bearer abc sk-example ghp_example xoxb-example github_pat_example "
                + "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature";

        assertThat(ToolDetailRedactor.redact(detail, false)).isEqualTo("[가림] [가림] [가림] [가림] [가림] [가림]");
    }

    @Test
    @DisplayName("긴 hex와 base64 값은 가린다")
    void redactsLongHexAndBase64Values() {
        String detail = "0123456789abcdef0123456789abcdef " + "QWxhZGRpbjpvcGVuIHNlc2FtZVNlY3JldA==";

        assertThat(ToolDetailRedactor.redact(detail, false)).isEqualTo("[가림] [가림]");
    }

    @Test
    @DisplayName("일반 문장의 비밀 키와 따옴표로 감싼 값도 가린다")
    void redactsSecretAssignmentsInProse() {
        assertThat(ToolDetailRedactor.redact("실행 password='two words' api_key=short; scope=notes", false))
                .isEqualTo("실행 password=[가림] api_key=[가림]; scope=notes");
    }

    @Test
    @DisplayName("Authorization 헤더와 여러 xox 계열의 짧은 비밀값도 가린다")
    void redactsAuthorizationHeaderAndOtherSlackTokenPrefixes() {
        assertThat(ToolDetailRedactor.redact("Authorization: Bearer short-secret", false))
                .doesNotContain("short-secret");
        assertThat(ToolDetailRedactor.redact("Authorization: Basic short-secret\nstatus=ok", false))
                .isEqualTo("Authorization: [가림]\nstatus=ok");
        assertThat(ToolDetailRedactor.redact("Cookie: first=tiny; second=short-secret", false))
                .doesNotContain("tiny", "short-secret");
        assertThat(ToolDetailRedactor.redact("xoxc-shortvalue xoxe-shortvalue", false))
                .isEqualTo("[가림] [가림]");
    }

    @Test
    @DisplayName("금액과 날짜와 짧은 식별자와 일반 문장은 보존한다")
    void preservesOrdinaryTextAndValues() {
        String detail = "금액 12,000원, 날짜 2026-10-01, 2026.10.01, www.example.com, id=abc-12, 오늘 날씨를 찾았다";

        assertThat(ToolDetailRedactor.redact(detail, false)).isEqualTo(detail);
        assertThat(ToolDetailRedactor.redact("2026-10-01/2026-10-31/2027-01-01", false))
                .isEqualTo("2026-10-01/2026-10-31/2027-01-01");
        assertThat(ToolDetailRedactor.redact(null, false)).isNull();
        assertThat(ToolDetailRedactor.redact("", false)).isEmpty();
    }

    @Test
    @DisplayName("가릴 것이 없는 인자는 공백과 키 순서와 글자 표기가 달라도 가려지는 것이 없다고 답한다")
    void argumentsWithoutSecretsHideNothingRegardlessOfSerialization() {
        String plain = "{\"to\":\"friend@example.com\",\"subject\":\"저녁 약속 🍜\","
                + "\"body\":\"안녕하세요.\\n\\\"내일\\\" 7시에 봬요 😀\\t끝\",\"count\":2,\"ratio\":1.50,\"tags\":[\"가\",\"나\"]}";
        String spaced =
                "{ \"subject\" : \"저녁 약속 🍜\",\n  \"to\": \"friend@example.com\", \"nested\": {\"b\": 1, \"a\": null} }";
        String escaped = "{\"body\":\"\\uc548\\ub155 \\ud83d\\ude00 a\\/b\"}";

        assertThat(ToolDetailRedactor.hidesArguments(plain))
                .as("한글, 이모지, 줄바꿈, 따옴표")
                .isFalse();
        assertThat(ToolDetailRedactor.hidesArguments(spaced)).as("공백과 키 순서").isFalse();
        assertThat(ToolDetailRedactor.hidesArguments(escaped)).as("\\u 로 적은 글자").isFalse();
        assertThat(ToolDetailRedactor.hidesArguments("{}")).isFalse();
        assertThat(ToolDetailRedactor.hidesArguments("본문만 있는 글")).isFalse();
        assertThat(ToolDetailRedactor.hidesArguments(null)).isFalse();
    }

    @Test
    @DisplayName("가려지는 글이 하나라도 있거나 JSON 으로 읽히지 않는 인자는 가려지는 것이 있다고 답한다")
    void argumentsWithSecretsHideSomething() {
        String token = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU2Nzg5";

        assertThat(ToolDetailRedactor.hidesArguments("{\"body\":\"회의록입니다\\n" + token + "\"}"))
                .isTrue();
        assertThat(ToolDetailRedactor.hidesArguments("{\"body\":\"안녕\",\"api_token\":\"abc\"}"))
                .isTrue();
        assertThat(ToolDetailRedactor.hidesArguments("{\"body\":\"password=hunter2 예요\"}"))
                .isTrue();
        assertThat(ToolDetailRedactor.hidesArguments("{\"body\":\"끝나지 않은 글")).isTrue();
        assertThat(ToolDetailRedactor.hidesArguments("실행 password=hunter2")).isTrue();
    }

    @Test
    @DisplayName("식별자로 선언한 맨 위 칸의 식별자 모양 값은 길이로 가리지 않고 나머지 칸은 그대로 가린다")
    void declaredIdentifiersAreNotHiddenByLength() {
        String id = "ANe1BmhXxP8kq3Lr0sT9vUwYzA2bC4dE6fG8hJ";
        String token = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU2Nzg5";
        String args = "{\"filter_id\":\"" + id + "\",\"ids\":[\"" + id + "\",\"Label_1\"],\"body\":\"" + token
                + "\",\"nested\":{\"filter_id\":\"" + id + "\"}}";

        String redacted = ToolDetailRedactor.redactArguments(args, Set.of("filter_id", "ids"));

        assertThat(redacted)
                .isEqualTo("{\"filter_id\":\"" + id + "\",\"ids\":[\"" + id + "\",\"Label_1\"],"
                        + "\"body\":\"[가림]\",\"nested\":{\"filter_id\":\"[가림]\"}}");
        assertThat(ToolDetailRedactor.hidesArguments("{\"filter_id\":\"" + id + "\"}", Set.of("filter_id")))
                .isFalse();
        assertThat(ToolDetailRedactor.hidesArguments("{\"filter_id\":\"" + id + "\"}", Set.of()))
                .as("선언이 없으면 지금처럼 가린다")
                .isTrue();
    }

    @Test
    @DisplayName("식별자 칸이어도 비밀 키 이름, 알려진 접두사, 식별자 모양이 아닌 값은 가린다")
    void declaredIdentifiersStillHideSecrets() {
        String id = "ANe1BmhXxP8kq3Lr0sT9vUwYzA2bC4dE6fG8hJ";
        Set<String> declared = Set.of("filter_id", "api_token");

        assertThat(ToolDetailRedactor.redactArguments("{\"api_token\":\"abc\"}", declared))
                .isEqualTo("{\"api_token\":\"[가림]\"}");
        assertThat(ToolDetailRedactor.redactArguments("{\"filter_id\":\"ghp_" + id + "\"}", declared))
                .isEqualTo("{\"filter_id\":\"[가림]\"}");
        assertThat(ToolDetailRedactor.redactArguments("{\"filter_id\":\"sk-" + id + "\"}", declared))
                .isEqualTo("{\"filter_id\":\"[가림]\"}");
        assertThat(ToolDetailRedactor.redactArguments("{\"filter_id\":\"AIza" + id + "\"}", declared))
                .as("Google API key 접두사")
                .isEqualTo("{\"filter_id\":\"[가림]\"}");
        assertThat(ToolDetailRedactor.hidesArguments("{\"filter_id\":[\"ok_id\",\"ghp_" + id + "\"]}", declared))
                .as("배열 항목의 접두사 key")
                .isTrue();
        assertThat(ToolDetailRedactor.redactArguments("{\"filter_id\":\"desk-" + id + "\"}", declared))
                .as("값 중간의 sk- 는 식별자의 일부다")
                .isEqualTo("{\"filter_id\":\"desk-" + id + "\"}");
        assertThat(ToolDetailRedactor.hidesArguments("{\"filter_id\":\"본문 " + id + "\"}", declared))
                .as("공백이 섞인 값")
                .isTrue();
        assertThat(ToolDetailRedactor.hidesArguments("{\"filter_id\":\"" + "a".repeat(257) + "\"}", declared))
                .as("256자를 넘는 값")
                .isTrue();
        assertThat(ToolDetailRedactor.hidesArguments("{\"filter_id\":[\"" + id + "\",{\"x\":1}]}", declared))
                .as("문자열이 아닌 항목이 섞인 배열은 평소대로 가린다")
                .isTrue();
        assertThat(ToolDetailRedactor.hidesArguments("{\"filter_id\":[\"" + id + "\",\"a b\"]}", declared))
                .as("배열의 한 항목이라도 식별자 모양이 아니면 배열 전체를 평소대로 가린다")
                .isTrue();
        assertThat(ToolDetailRedactor.hidesArguments("{\"filter_id\":\"끝나지 않은", declared))
                .isTrue();
        assertThat(ToolDetailRedactor.hidesArguments("[\"" + id + "\"]", declared))
                .as("맨 위가 객체가 아니면 선언을 쓰지 않는다")
                .isTrue();
    }

    @Test
    @DisplayName("연결용 도구는 짧고 평범한 비밀값도 원문 전체를 가려 보호한다")
    void hidesAllConnectorDetails() {
        assertThat(ToolDetailRedactor.redact("결과: very short secret", true)).isEqualTo("[연결 도구 내용 가림]");
        assertThat(ToolDetailRedactor.redact(null, true)).isNull();
    }

    @Test
    @DisplayName("손상된 JSON의 비밀값은 원문 전체를 가려 보호한다")
    void hidesMalformedJson() {
        assertThat(ToolDetailRedactor.redact("{\"password\":\"unfinished", false))
                .isEqualTo("[가림]");
        assertThat(ToolDetailRedactor.redact("실행 token={\"a\":{\"b\":\"short\"}}", false))
                .isEqualTo("[가림]");
    }

    @Test
    @DisplayName("비밀값을 가린 뒤 기존 저장 상한으로 자르고 과도한 입력은 전부 가린다")
    void limitsLengthAfterRedaction() {
        String detail = "가".repeat(490) + " sk-" + "x".repeat(100);

        assertThat(ToolDetailRedactor.redact(detail, false)).isEqualTo("가".repeat(490) + " [가림]");
        assertThat(ToolDetailRedactor.redact("가".repeat(600), false))
                .hasSize(500)
                .endsWith("…");
        assertThat(ToolDetailRedactor.redact("가".repeat(65_537), false)).isEqualTo("[긴 도구 내용 가림]");
    }

    @Test
    @DisplayName("커넥터 READ 본문이 일반 도구의 인자로 다시 실리면 비밀 모양만 가리고 나머지 본문은 앞 500자까지 남는다")
    void keepsReadBodyReusedAsOtherToolArguments() {
        // docs/read-data-flow.md 의 RF-16 이다. 실행 기록이 커넥터 READ 본문을 어디까지 남기는지 고정한다.
        String mail = "합성 메일 본문: 다음 주 화요일 병원 예약";
        String token = "sk-" + "x".repeat(40);
        String args = "{\"url\":\"https://collector.example/?d=" + mail + "\",\"api_key\":\"" + token + "\"}";

        String recorded = ToolDetailRedactor.redact(args, false);

        assertThat(recorded).contains(mail).doesNotContain(token);
        assertThat(ToolDetailRedactor.redact(args, true)).isEqualTo("[연결 도구 내용 가림]");
    }

    @Test
    @DisplayName("상한 상수는 500이고 실행 사건의 상한과 같다")
    void detailLimitIsSharedWithExecutionEvent() {
        assertThat(ToolDetailRedactor.DETAIL_LIMIT).isEqualTo(500);
        assertThat(ExecutionEvent.DETAIL_LIMIT).isEqualTo(ToolDetailRedactor.DETAIL_LIMIT);
    }
}
