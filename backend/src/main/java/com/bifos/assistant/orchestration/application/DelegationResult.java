package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import java.util.Objects;

/**
 * {@code agent_delegate} 한 번의 결과다. 시작했으면 실행 번호와 상태가, 거절했으면 실패 코드만 있다.
 *
 * @param executionId 시작했거나 같은 호출로 이미 있던 실행의 번호. 거절이면 null 이다
 * @param status 그 실행의 상태. 새로 시작했으면 {@code RUNNING} 이다. 거절이면 null 이다
 * @param failure 거절한 까닭. 시작했으면 null 이다
 */
public record DelegationResult(Long executionId, ExecutionStatus status, Failure failure) {

    /** 밖으로 나가는 실패 코드다. 이유는 서버 로그에만 남긴다. */
    public enum Failure {
        /** 없는 에이전트와 요청자가 쓸 수 없는 에이전트. 어느 쪽인지 알리지 않는다 */
        AGENT_UNAVAILABLE,
        AGENT_DISABLED,
        DEPTH_EXCEEDED,
        /** 한 루트 아래 도는 위임 자식이 한도에 닿았다 */
        TOO_MANY_CHILDREN,
        /** 서버 전체의 동시 위임이나 그 사용자의 동시 실행이 한도에 닿았다 */
        BUSY,
        SUBMIT_FAILED,
        /** 먼저 살펴보기 트리에서 요청자의 커넥터 에이전트가 아닌 곳에 맡기려 했다(ADR-077) */
        CHECK_TARGET,
        /** 먼저 살펴보기 트리에서 맡긴 수가 상한에 닿았거나 그 살펴보기가 이미 끝났다(ADR-077) */
        CHECK_LIMIT
    }

    public static DelegationResult started(Long executionId, ExecutionStatus status) {
        return new DelegationResult(Objects.requireNonNull(executionId), Objects.requireNonNull(status), null);
    }

    public static DelegationResult rejected(Failure failure) {
        return new DelegationResult(null, null, Objects.requireNonNull(failure));
    }

    public boolean accepted() {
        return failure == null;
    }
}
