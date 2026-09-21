package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 요청에 provider 와 모델을 둘 다 실어야 한다는 것과 막힘 판정 문자열을 본다. */
class HermesRunRequestTest {

    private final HermesProfileKeyStore keyStore = mock(HermesProfileKeyStore.class);
    private final HttpHermesRunsClient client = new HttpHermesRunsClient(
            keyStore,
            new HermesProperties(
                    "keys",
                    "https://hermes-dashboard.example.com",
                    "test-dashboard-token",
                    "https://hermes-listener.example.com",
                    Duration.ofMillis(10),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1)));

    @Test
    void provider_가_비면_Hermes_를_부르지_않고_실패한다() {
        assertThatThrownBy(() -> client.submit(command(null, "gpt-5.5")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(keyStore);
    }

    @Test
    void 모델이_비면_Hermes_를_부르지_않고_실패한다() {
        assertThatThrownBy(() -> client.submit(command("openai-codex", " ")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(keyStore);
    }

    /**
     * 그 provider 의 계정이 전부 막혔을 때만 다음 순위로 넘어간다.
     *
     * <p>{@code HTTP 404} 와 {@code HTTP 402} 는 상류 provider 가 보낸 글이라 문구가 바뀔 수 있어
     * 판정에 쓰지 않는다.
     */
    @Test
    void 계정이_전부_막힌_실패만_넘김_대상이다() {
        assertThat(failed(HermesRunResult.PROVIDER_AUTH_FAILED_PREFIX + " No Codex credentials stored.")
                        .providerBlocked())
                .isTrue();
        assertThat(failed("HTTP 404: 404 page not found").providerBlocked()).isFalse();
        assertThat(failed("HTTP 402: This request requires more credits").providerBlocked()).isFalse();
        assertThat(failed(null).providerBlocked()).isFalse();
    }

    @Test
    void 성공한_실행은_넘김_대상이_아니다() {
        HermesRunResult completed = new HermesRunResult(
                "run-1", "sess-1", "completed", "네", "gpt-5.5", "openai-codex",
                HermesRunResult.PROVIDER_AUTH_FAILED_PREFIX + " 남아 있는 글", TokenUsage.empty());

        assertThat(completed.providerBlocked()).isFalse();
    }

    private static HermesRunResult failed(String error) {
        return new HermesRunResult(
                "run-1", "sess-1", "failed", null, "gpt-5.5", "openai-codex", error, TokenUsage.empty());
    }

    private static HermesRunCommand command(String provider, String model) {
        return new HermesRunCommand(
                "dad", "http://runtime.test/p/dad", "안녕", null, null, provider, model);
    }
}
