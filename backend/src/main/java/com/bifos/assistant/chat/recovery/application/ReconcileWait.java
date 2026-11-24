package com.bifos.assistant.chat.recovery.application;

import com.bifos.assistant.hermes.config.HermesProperties;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import java.time.Duration;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 기동 정리가 상한을 설정하지 않았을 때 줄 하나에 다시 붙어 기다리는 상한이다. 그 줄이 처음 돌 때 쓴 한도와 맞춘다.
 *
 * <p>맡긴 실행(부모가 있거나 위임 키가 있는 줄)과 사람이 없는 루트는 {@code hermes.background-run-timeout}, 나머지는
 * {@code hermes.run-timeout} 이다. 실행 줄은 turn 의 종류를 모르므로 사람이 없는 루트를 모두 긴 쪽으로 본다. 먼저 살펴보기와 그 결과를
 * 전하는 turn 도 여기에 들어 기동 뒤에는 처음보다 길게 기다릴 수 있다. 규칙은 {@code docs/features/execution.md} 의 「실행 한도」 가
 * 갖는다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class ReconcileWait {

    static Duration of(AgentExecution row, HermesProperties hermes) {
        boolean background = row.parentExecutionId() != null || row.delegationKey() != null || row.unattended();
        return background ? hermes.backgroundRunTimeout() : hermes.runTimeout();
    }
}
