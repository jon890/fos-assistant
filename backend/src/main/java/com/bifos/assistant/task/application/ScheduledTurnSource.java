package com.bifos.assistant.task.application;

import com.bifos.assistant.chat.application.ScheduledTurnExecutions;
import com.bifos.assistant.task.infra.TaskRunRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 실행 가운데 예약 작업 발화의 루트 실행을 가려 첫 반응 시간 집계가 뺄 수 있게 한다.
 *
 * <p>트랜잭션을 열지 않아 조회 하나가 자기 트랜잭션으로 돈다. 빈 {@code in} 절은 데이터베이스마다 다르게 동작하므로 번호가 비면
 * 읽지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ScheduledTurnSource implements ScheduledTurnExecutions {

    private final TaskRunRepository runs;

    @Override
    public Set<Long> scheduledAmong(Collection<Long> executionIds) {
        if (executionIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(runs.findExecutionIdsIn(executionIds));
    }
}
