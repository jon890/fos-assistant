package com.bifos.assistant.usage.execution.application;

import com.bifos.assistant.hermes.application.dto.HermesRunCommand;
import com.bifos.assistant.hermes.application.port.HermesRunsClient;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.LostRunCandidateRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 새 실행을 Hermes 에 보내기 전에, 같은 profile 에서 Hermes 가 잃었을 수 있는 지난 실행을 해제한다.
 *
 * <p>Hermes v0.23.2 는 재기동으로 잃은 실행을 해제하기 전까지 같은 session 의 뒤 실행을 대기에 둔다. 그 실행을 다시 묻는 쪽이 모두 그만둔
 * 뒤에도 대화가 막히지 않게 한다. 고르는 조건은 우리 실행 줄의 상태와 오류 코드뿐이다. 무엇을 잃은 것으로 보고 어떻게 해제할지는
 * {@link HermesRunsClient#releaseIfLost} 가 정한다(docs/features/hermes-upgrade/design.md 의 「잃은 실행 미리 풀기」).
 *
 * <p>{@code CANCELLED} 줄은 고르지 않는다. 사용자 중지는 흔해서 상한을 채워 실제로 잃은 실행을 밀어낸다. 중지한 run 이 잃은 것이면
 * 중지의 409 처리나 남은 원격 실행 기다리기가 해제한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LostRunRelease {

    /** 고르는 기간이다. 이보다 오래된 잃은 실행은 이미 다른 경로가 해제했거나 그 대화를 더 쓰지 않는다. */
    static final Duration WINDOW = Duration.ofHours(24);

    /** 한 번에 넘기는 수의 상한이다. */
    static final int LIMIT = 10;

    /**
     * Hermes 와 닿지 못했거나 기다리다 그만둔 실패의 코드다. 잃은 run 은 이 실패로만 남는다.
     * {@code RECONCILE_*} 의 원본은 {@code chat.recovery.application.RestartReconciler} 다. 의존 방향 때문에 문자열로 둔다.
     */
    static final List<String> LOST_CODES = List.of(
        ErrorCode.HERMES_UNAVAILABLE.name(),
        ErrorCode.HERMES_RUN_TIMEOUT.name(),
        "RECONCILE_TIMEOUT",
        "RECONCILE_UNREACHABLE");

    private final LostRunCandidateRepository executions;
    private final HermesRunsClient hermes;
    private final Clock clock;


    /**
     * 그 명령의 profile 에서 잃었을 수 있는 실행을 넘긴다. 실패해도 제출을 막지 않는다.
     *
     * <p>한 번이라도 실패하면 Hermes 가 닿지 않는 것으로 보고 남은 후보를 건너뛴다. 제출 전 지연을 조회 한 번으로 묶는다.
     */
    public void beforeSubmit(HermesRunCommand command) {
        String runId = null;
        try {
            List<String> runIds = executions.findLostRunCandidates(
                command.profileName(),
                ExecutionStatus.FAILED,
                LOST_CODES,
                clock.instant().minus(WINDOW),
                PageRequest.of(0, LIMIT));
            for (String candidate : runIds) {
                runId = candidate;
                hermes.releaseIfLost(command.apiBaseUrl(), command.profileName(), candidate);
            }
        } catch (RuntimeException ex) {
            // 저장소 조회 실패도 여기서 삼킨다. 부르는 쪽의 실패 처리는 제출의 실패만 다룬다.
            log.warn("잃었을 수 있는 실행을 확인하지 못해 남은 후보를 건너뛴다 profile={} runId={}", command.profileName(), runId, ex);
        }
    }
}
