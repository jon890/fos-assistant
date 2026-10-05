package com.bifos.assistant.usage.application;

import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 먼저 알리기의 판정이 읽는 실행 줄을 낸다.
 *
 * <p>{@code attention} 이 이 패키지의 저장소를 바로 import 하지 않도록 읽기 메서드만 둔다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AttentionExecutionQuery {

    private final AgentExecutionRepository executions;

    /** {@code since} 뒤에 실패했고 같은 대화에서 아직 성공으로 다시 돌지 않은 대화 turn 루트 실행이다. 최근 순이다. */
    public List<AgentExecution> unresolvedFailedTurns(Long userId, Instant since) {
        return executions.findUnresolvedFailedTurns(userId, since);
    }

    /** 도는 중이거나 {@code finishedSince} 뒤에 끝난 위임 실행이다. 최근 순이다. */
    public List<AgentExecution> delegations(Long userId, Instant finishedSince) {
        return executions.findDelegationsForAttention(userId, finishedSince);
    }
}
