package com.bifos.assistant.usage.execution.application;

import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이미 있는 위임 답 한 줄을 암호화하거나 평문으로 되돌린다(ADR-20261008 / data-encryption).
 *
 * <p>줄마다 새 트랜잭션에서 실행 줄을 쓰기 잠금으로 다시 읽고 조건을 다시 본다. 그 사이 다른 차례가 먼저 고쳤으면 건너뛴다. 암호화한 줄은
 * 같은 트랜잭션에서 풀어 봐서 평문과 다르면 되돌린다. AAD 는 {@link ExecutionOutputs} 가 정한 그대로 쓴다.
 */
@Component
@RequiredArgsConstructor
public class ExecutionOutputSealer {

    private final AgentExecutionRepository executions;
    private final ExecutionOutputs outputs;


    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean seal(Long executionId) {
        AgentExecution execution = executions.lockById(executionId).orElse(null);
        if (execution == null
            || execution.outputKeyId() != null
            || execution.userId() == null
            || execution.outputText() == null
            || execution.outputText().isEmpty()) {
            return false;
        }
        String plain = execution.outputText();
        outputs.record(execution, plain);
        executions.flush();
        if (!outputs.opensTo(execution, plain)) {
            throw new IllegalStateException("the sealed output does not open to the original");
        }
        return true;
    }


    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean unseal(Long executionId) {
        AgentExecution execution = executions.lockById(executionId).orElse(null);
        if (execution == null || execution.outputKeyId() == null || !outputs.unseal(execution)) {
            return false;
        }
        executions.flush();
        return true;
    }
}
