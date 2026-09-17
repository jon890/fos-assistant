package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.hermes.dto.TokenUsage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the shape a real Hermes v0.21.0 run status returns.
 *
 * <p>Captured from the home server on 2026-09-17. It reports
 * neither a provider nor cached tokens, and its {@code model} is the API server's model name, which
 * defaults to the profile name.
 */
class RealHermesResponseShapeTest {

    private static final String RUN_STATUS =
            """
            {
              "object": "hermes.run",
              "run_id": "run_8b4418b4886843f08408f924621ec005",
              "status": "completed",
              "session_id": "run_8b4418b4886843f08408f924621ec005",
              "model": "bifos",
              "last_event": "run.completed",
              "output": "안녕하세요, 무엇을 도와드릴까요?",
              "usage": {"input_tokens": 561, "output_tokens": 15, "total_tokens": 576}
            }
            """;

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void reads_the_token_counts_a_real_run_reports() {
        TokenUsage usage = HttpHermesRunsClient.readUsage(mapper.readTree(RUN_STATUS).path("usage"));

        assertThat(usage.inputTokens()).isEqualTo(561);
        assertThat(usage.outputTokens()).isEqualTo(15);
        assertThat(usage.totalTokens()).isEqualTo(576);
        assertThat(usage.cachedInputTokens()).isNull();
    }

    @Test
    void the_run_reports_the_profile_name_where_a_model_would_go() {
        assertThat(mapper.readTree(RUN_STATUS).get("model").asString()).isEqualTo("bifos");
        assertThat(mapper.readTree(RUN_STATUS).get("provider")).isNull();
    }
}
