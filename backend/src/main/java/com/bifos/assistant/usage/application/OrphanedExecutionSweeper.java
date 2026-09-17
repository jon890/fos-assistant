package com.bifos.assistant.usage.application;

import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 기동 전에 끊긴 실행 기록을 실패로 마무리한다. */
@Component
@RequiredArgsConstructor
public class OrphanedExecutionSweeper {

    private static final Logger log = LoggerFactory.getLogger(OrphanedExecutionSweeper.class);
    private static final String ORPHANED = "ORPHANED";

    private final AgentExecutionRepository executions;

    /** Flyway가 끝난 뒤에 실행 중으로 남은 기록을 한 번 정리한다. */
    @EventListener(ApplicationReadyEvent.class)
    public void sweep() {
        List<AgentExecution> running = executions.findByStatus(ExecutionStatus.RUNNING);
        if (running.isEmpty()) {
            return;
        }

        Instant finishedAt = Instant.now();
        running.forEach(execution -> execution.markFailed(ORPHANED, finishedAt));
        executions.saveAll(running);
        log.info("기동 전에 끊긴 실행 {}건을 정리했습니다", running.size());
    }
}
