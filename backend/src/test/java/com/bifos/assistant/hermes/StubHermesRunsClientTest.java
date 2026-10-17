package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.hermes.application.dto.HermesRunLookup;
import com.bifos.assistant.hermes.application.dto.HermesRunResult;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StubHermesRunsClientTest {

    @Test
    @DisplayName("응답을 순서대로 내고 마지막 응답은 교체할 때까지 되풀이한다")
    void repeatsLastLookupAnswerUntilReplaced() {
        StubHermesRunsClient stub = new StubHermesRunsClient();
        HermesRunLookup running = HermesRunLookup.running();
        HermesRunLookup finished = HermesRunLookup.finished(
            HermesRunResult.of("run", "session", "completed", "답", "model", "provider", null));
        stub.willLookup("run", running, finished);

        assertThat(stub.lookupRun("unused", "profile", "run")).isSameAs(running);
        assertThat(stub.lookupRun("unused", "profile", "run")).isSameAs(finished);
        assertThat(stub.lookupRun("unused", "profile", "run")).isSameAs(finished);

        stub.willLookup("run", running);
        assertThat(stub.lookupRun("unused", "profile", "run")).isSameAs(running);
        assertThat(stub.lookupRun("unused", "profile", "run")).isSameAs(running);
        stub.willLookup("run");
        assertThat(stub.lookupRun("unused", "profile", "run").state()).isEqualTo(HermesRunLookup.State.NOT_FOUND);
        stub.willLookup("run", running);
        stub.reset();
        assertThat(stub.lookupRun("unused", "profile", "run").state()).isEqualTo(HermesRunLookup.State.NOT_FOUND);
    }


    @Test
    @DisplayName("응답을 바꾸는 중에도 등록된 run 을 없다고 답하지 않는다")
    void keepsRegisteredRunVisibleWhileReplacingLookupAnswers() {
        StubHermesRunsClient stub = new StubHermesRunsClient();
        String runId = "run-replacement";
        stub.willLookup(runId, HermesRunLookup.running());
        // 마지막 응답을 되풀이하는 상태에서 응답 교체와 조회를 겹친다.
        stub.lookupRun("unused", "profile", runId);
        // 시작만 맞추므로 수정 전 경합의 재현은 확률적이다.
        // 수정 후에는 교체 전후 어느 응답을 읽어도 등록된 run 이 사라지지 않아야 한다.
        CyclicBarrier start = new CyclicBarrier(2);
        CyclicBarrier end = new CyclicBarrier(2);
        CompletableFuture<Void> replacing = CompletableFuture.runAsync(() -> {
            for (int attempt = 0; attempt < 4_000; attempt++) {
                await(start);
                stub.willLookup(runId, HermesRunLookup.running());
                await(end);
            }
        });
        CompletableFuture<Void> lookingUp = CompletableFuture.runAsync(() -> {
            int missing = 0;
            for (int attempt = 0; attempt < 4_000; attempt++) {
                stub.lookupRun("unused", "profile", runId);
                await(start);
                HermesRunLookup lookup = stub.lookupRun("unused", "profile", runId);
                await(end);
                if (lookup.state() == HermesRunLookup.State.NOT_FOUND) {
                    missing++;
                }
            }
            assertThat(missing).as("응답 교체 중 사라진 run 조회 수").isZero();
        });
        CompletableFuture.allOf(replacing, lookingUp).join();
    }


    @Test
    @DisplayName("정한 횟수만 조회를 실패시키고 0으로 바꾸면 실패를 해제한다")
    void limitsLookupFailuresAndCanClearThem() {
        StubHermesRunsClient stub = new StubHermesRunsClient();
        ApiException failure = new ApiException(ErrorCode.HERMES_UNAVAILABLE, "조회 실패");
        stub.willLookup("run", HermesRunLookup.running());
        stub.willFailLookup("run", failure, 2);

        assertThatThrownBy(() -> stub.lookupRun("unused", "profile", "run")).isSameAs(failure);
        assertThatThrownBy(() -> stub.lookupRun("unused", "profile", "run")).isSameAs(failure);
        assertThat(stub.lookupRun("unused", "profile", "run").state()).isEqualTo(HermesRunLookup.State.RUNNING);

        stub.willFailLookup("run", failure, Integer.MAX_VALUE);
        stub.willFailLookup("run", failure, 0);
        assertThat(stub.lookupRun("unused", "profile", "run").state()).isEqualTo(HermesRunLookup.State.RUNNING);
    }


    @Test
    @DisplayName("조회 실패를 바꾸는 중에도 예외와 남은 횟수를 함께 읽는다")
    void readsLookupFailureAndCountFromSameConfiguration() {
        StubHermesRunsClient stub = new StubHermesRunsClient();
        String runId = "run-failure-replacement";
        ApiException previousFailure = new ApiException(ErrorCode.HERMES_UNAVAILABLE, "이전 실패");
        ApiException replacementFailure = new ApiException(ErrorCode.HERMES_UNAVAILABLE, "해제한 실패");
        stub.willLookup(runId, HermesRunLookup.running());
        CyclicBarrier start = new CyclicBarrier(2);
        CyclicBarrier end = new CyclicBarrier(2);
        CompletableFuture<Void> replacing = CompletableFuture.runAsync(() -> {
            for (int attempt = 0; attempt < 4_000; attempt++) {
                await(start);
                stub.willFailLookup(runId, replacementFailure, 0);
                await(end);
            }
        });
        CompletableFuture<Void> lookingUp = CompletableFuture.runAsync(() -> {
            int mixed = 0;
            for (int attempt = 0; attempt < 4_000; attempt++) {
                stub.willFailLookup(runId, previousFailure, 1);
                await(start);
                try {
                    stub.lookupRun("unused", "profile", runId);
                } catch (ApiException ex) {
                    // 교체 전 예외 한 번 또는 교체 후 정상 응답은 가능하다. 새 예외와 옛 횟수의 조합은 불가능해야 한다.
                    if (ex == replacementFailure) {
                        mixed++;
                    }
                }
                await(end);
            }
            assertThat(mixed).as("다른 설정의 예외와 횟수를 섞어 읽은 수").isZero();
        });
        CompletableFuture.allOf(replacing, lookingUp).join();
    }


    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
