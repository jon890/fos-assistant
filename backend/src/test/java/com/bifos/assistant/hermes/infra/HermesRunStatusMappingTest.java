package com.bifos.assistant.hermes.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.hermes.application.dto.HermesRunLookup;
import com.bifos.assistant.hermes.application.dto.HermesRunResult;
import com.bifos.assistant.runtime.application.dto.RunLookup;
import com.bifos.assistant.runtime.application.dto.RunOutcome;
import com.bifos.assistant.runtime.application.dto.RunResult;
import com.bifos.assistant.runtime.application.dto.SessionRuntime;
import com.bifos.assistant.runtime.domain.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HermesRunStatusMappingTest {

    /**
     * Hermes 의 종료 상태는 HttpHermesRunsClient 의 completed, failed, cancelled 와 HermesSessionRuns 가 만드는
     * unknown, interrupted 다.
     */
    @ParameterizedTest
    @DisplayName("종료 상태 글자는 받은 그대로 남기고 성공·취소·실패를 가려 옮긴다")
    @CsvSource({
        "completed, SUCCEEDED",
        "COMPLETED, SUCCEEDED",
        "cancelled, CANCELLED",
        "Cancelled, CANCELLED",
        "failed, FAILED",
        "interrupted, FAILED",
        "unknown, FAILED"
    })
    void mapsTerminalStatuses(String status, RunOutcome outcome) {
        RunResult result = HermesRunOutcomes.toRunResult(hermesResult(status, null));

        assertThat(result.statusLabel()).isEqualTo(status);
        assertThat(result.outcome()).isEqualTo(outcome);
        assertThat(result.succeeded()).isEqualTo(outcome == RunOutcome.SUCCEEDED);
    }


    @Test
    @DisplayName("provider 계정이 막힌 실패만 막힘으로 옮긴다")
    void mapsProviderBlocked() {
        String blocked = HermesRunResult.PROVIDER_AUTH_FAILED_PREFIX + " no credentials";

        assertThat(HermesRunOutcomes.toRunResult(hermesResult("failed", blocked)).providerBlocked()).isTrue();
        assertThat(HermesRunOutcomes.toRunResult(hermesResult("failed", "upstream 500")).providerBlocked()).isFalse();
        assertThat(HermesRunOutcomes.toRunResult(hermesResult("completed", blocked)).providerBlocked()).isFalse();
    }


    @Test
    @DisplayName("결과의 나머지 칸을 그대로 옮긴다")
    void keepsOtherFields() {
        TokenUsage usage = new TokenUsage(1L, 2L, 3L, 6L);
        SessionRuntime runtime = new SessionRuntime("model-real", "openai-codex");
        HermesRunResult hermes = new HermesRunResult(
            "run-1", "session-1", "failed", "답", "model-asked", "provider-asked", "오류", usage, runtime, "못 끼운 글",
            false);

        RunResult result = HermesRunOutcomes.toRunResult(hermes);

        assertThat(result).isEqualTo(new RunResult(
            "run-1", "session-1", RunOutcome.FAILED, "failed", "답", "model-asked", "provider-asked", "오류", usage,
            runtime, "못 끼운 글", false, false));
    }


    @Test
    @DisplayName("조회 답의 세 상태를 그대로 옮긴다")
    void mapsLookupStates() {
        assertThat(HermesRunOutcomes.toRunLookup(HermesRunLookup.running()).state()).isEqualTo(RunLookup.State.RUNNING);
        assertThat(HermesRunOutcomes.toRunLookup(HermesRunLookup.notFound()).state())
            .isEqualTo(RunLookup.State.NOT_FOUND);

        RunLookup finished = HermesRunOutcomes.toRunLookup(HermesRunLookup.finished(hermesResult("cancelled", null)));

        assertThat(finished.state()).isEqualTo(RunLookup.State.FINISHED);
        assertThat(finished.result().outcome()).isEqualTo(RunOutcome.CANCELLED);
    }


    private static HermesRunResult hermesResult(String status, String error) {
        return new HermesRunResult("run-1", "session-1", status, "답", null, null, error, TokenUsage.empty());
    }
}
