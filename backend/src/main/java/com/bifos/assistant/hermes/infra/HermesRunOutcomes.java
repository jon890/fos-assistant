package com.bifos.assistant.hermes.infra;

import com.bifos.assistant.hermes.application.dto.HermesRunLookup;
import com.bifos.assistant.hermes.application.dto.HermesRunResult;
import com.bifos.assistant.runtime.application.dto.RunLookup;
import com.bifos.assistant.runtime.application.dto.RunOutcome;
import com.bifos.assistant.runtime.application.dto.RunResult;

/** Hermes 의 끝난 실행과 조회 답을 런타임에 묶이지 않는 결과로 옮긴다. */
public final class HermesRunOutcomes {

    /** 실행을 부르는 쪽이 취소로 보던 Hermes 상태다. 옮기기 전에는 부르는 쪽이 이 글자와 직접 비교했다. */
    private static final String CANCELLED = "cancelled";


    private HermesRunOutcomes() {
    }


    /** 상태 글자는 받은 그대로 넘긴다. 실행 기록의 오류 코드가 그 글자로 남는다. */
    public static RunResult toRunResult(HermesRunResult result) {
        if (result == null) {
            return null;
        }
        return new RunResult(
            result.runId(),
            result.sessionId(),
            outcomeOf(result),
            result.status(),
            result.output(),
            result.model(),
            result.provider(),
            result.error(),
            result.usage(),
            result.runtime(),
            result.pendingSteer(),
            result.pendingSteerKnown(),
            result.providerBlocked());
    }


    public static RunLookup toRunLookup(HermesRunLookup lookup) {
        return switch (lookup.state()) {
            case RUNNING -> RunLookup.running();
            case FINISHED -> RunLookup.finished(toRunResult(lookup.result()));
            case NOT_FOUND -> RunLookup.notFound();
        };
    }


    private static RunOutcome outcomeOf(HermesRunResult result) {
        if (result.succeeded()) {
            return RunOutcome.SUCCEEDED;
        }
        return CANCELLED.equalsIgnoreCase(result.status()) ? RunOutcome.CANCELLED : RunOutcome.FAILED;
    }
}
