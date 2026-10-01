package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;

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
                .doesNotContain("short-secret");
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
}
