package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.hermes.dto.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** provider 마다 다른 usage 칸 이름을 읽고 보고하지 않은 값은 비워 둔다. */
class HermesUsageParsingTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private TokenUsage parse(String json) throws Exception {
        return HttpHermesRunsClient.readUsage(mapper.readTree(json));
    }

    @Test
    @DisplayName("Hermes v0 21 5 의 run usage 에서 캐시 읽기를 읽는다")
    void readsCacheReadFromRunUsageOfHermesV0215() throws Exception {
        // 실제 v0.21.5 run 응답의 키 모양이다(2026-09-29 운영에서 확인). input 은 캐시 읽기와 쓰기를 포함한다.
        TokenUsage usage = parse("""
                        {"input_tokens": 1200, "output_tokens": 40, "total_tokens": 1240,
                         "cache_read_tokens": 1024, "cache_write_tokens": 0}
                        """);

        assertThat(usage.inputTokens()).isEqualTo(1200);
        assertThat(usage.outputTokens()).isEqualTo(40);
        assertThat(usage.totalTokens()).isEqualTo(1240);
        assertThat(usage.cachedInputTokens()).isEqualTo(1024);
    }

    @Test
    @DisplayName("prompt tokens 와 completion tokens 를 읽는다")
    void readsTheOpenaiShape() throws Exception {
        TokenUsage usage = parse("""
                        {"prompt_tokens": 120, "completion_tokens": 40, "total_tokens": 160,
                         "prompt_tokens_details": {"cached_tokens": 80}}
                        """);

        assertThat(usage.inputTokens()).isEqualTo(120);
        assertThat(usage.outputTokens()).isEqualTo(40);
        assertThat(usage.totalTokens()).isEqualTo(160);
        assertThat(usage.cachedInputTokens()).isEqualTo(80);
    }

    @Test
    @DisplayName("input tokens 와 output tokens 를 읽고 합계를 계산한다")
    void readsTheAnthropicShapeAndDerivesTheTotal() throws Exception {
        TokenUsage usage = parse("""
                        {"input_tokens": 10, "output_tokens": 5, "cache_read_input_tokens": 3}
                        """);

        assertThat(usage.inputTokens()).isEqualTo(10);
        assertThat(usage.outputTokens()).isEqualTo(5);
        assertThat(usage.cachedInputTokens()).isEqualTo(3);
        assertThat(usage.totalTokens()).isEqualTo(15);
    }

    @Test
    @DisplayName("usage 에 토큰이 없으면 비워 둔다")
    void survivesAMissingUsageBlock() throws Exception {
        TokenUsage usage = parse("{}");

        assertThat(usage.inputTokens()).isNull();
        assertThat(usage.totalTokens()).isNull();
    }

    @Test
    @DisplayName("usage 와 캐시 칸 이름이 달라도 같은 토큰 수로 읽는다")
    void readsEquivalentCountsAcrossUsageFieldNames() throws Exception {
        TokenUsage expected = new TokenUsage(120L, 80L, 40L, 160L);
        for (String cache : new String[] {
            "\"cache_read_tokens\":80", "\"cached_tokens\":80",
            "\"prompt_tokens_details\":{\"cached_tokens\":80}", "\"cache_read_input_tokens\":80"
        }) {
            assertThat(parse("{\"input_tokens\":120,\"output_tokens\":40," + cache + "}"))
                    .isEqualTo(expected);
            assertThat(parse("{\"prompt_tokens\":120,\"completion_tokens\":40," + cache + "}"))
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("보고하지 않은 캐시와 출력은 비워 두고 total 로 메우지 않는다")
    void keepsMissingCountsUnknownInsteadOfDerivingFromTotal() throws Exception {
        assertThat(parse("{\"input_tokens\":120,\"total_tokens\":160}"))
                .isEqualTo(new TokenUsage(120L, null, null, 160L));
        assertThat(parse("{\"input_tokens\":120,\"output_tokens\":40}"))
                .isEqualTo(new TokenUsage(120L, null, 40L, 160L));
        assertThat(parse("null")).isEqualTo(TokenUsage.empty());
    }
}
