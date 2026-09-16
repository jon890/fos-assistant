package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.hermes.dto.TokenUsage;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

/** Providers disagree on usage field names, so the reader has to accept every shape we have seen. */
class HermesUsageParsingTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private TokenUsage parse(String json) throws Exception {
        return HttpHermesRunsClient.readUsage(mapper.readTree(json));
    }

    @Test
    void reads_the_openai_shape() throws Exception {
        TokenUsage usage =
                parse(
                        """
                        {"prompt_tokens": 120, "completion_tokens": 40, "total_tokens": 160,
                         "prompt_tokens_details": {"cached_tokens": 80}}
                        """);

        assertThat(usage.inputTokens()).isEqualTo(120);
        assertThat(usage.outputTokens()).isEqualTo(40);
        assertThat(usage.totalTokens()).isEqualTo(160);
        assertThat(usage.cachedInputTokens()).isEqualTo(80);
    }

    @Test
    void reads_the_anthropic_shape_and_derives_the_total() throws Exception {
        TokenUsage usage =
                parse("""
                        {"input_tokens": 10, "output_tokens": 5, "cache_read_input_tokens": 3}
                        """);

        assertThat(usage.inputTokens()).isEqualTo(10);
        assertThat(usage.outputTokens()).isEqualTo(5);
        assertThat(usage.cachedInputTokens()).isEqualTo(3);
        assertThat(usage.totalTokens()).isEqualTo(15);
    }

    @Test
    void survives_a_missing_usage_block() throws Exception {
        TokenUsage usage = parse("{}");

        assertThat(usage.inputTokens()).isNull();
        assertThat(usage.totalTokens()).isNull();
    }
}
