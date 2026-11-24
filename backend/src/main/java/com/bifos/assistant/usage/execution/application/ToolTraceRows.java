package com.bifos.assistant.usage.execution.application;

import com.bifos.assistant.usage.execution.application.dto.ToolTrace;
import com.bifos.assistant.usage.execution.infra.ExecutionEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이미 저장된 도구 완료 줄 하나에 plugin 기록을 옮기는 트랜잭션이다.
 *
 * <p>바꾸는 칸만 쓰는 질의({@link ExecutionEventRepository#recordToolTrace})를 부른다. {@link ToolTraceWriter} 가 사건마다 불리는
 * 자리에서 쓰므로 실제로 고칠 때만 트랜잭션을 열려고 따로 둔다.
 */
@Service
@RequiredArgsConstructor
public class ToolTraceRows {

    private final ExecutionEventRepository events;


    @Transactional
    public void record(Long eventId, ToolTrace trace) {
        events.recordToolTrace(eventId, trace.toolName(), trace.durationMs(), trace.errorType(), trace.errorSummary());
    }
}
