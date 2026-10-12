package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.hermes.dto.HermesRunLookup;
import com.bifos.assistant.hermes.dto.HermesRunResult;
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

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
