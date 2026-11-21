package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.usage.execution.domain.AgentExecution;
import java.io.Closeable;

/** {@link ChildRunEvents} 가 실행 하나를 기록하는 상태다. 필드는 이 객체를 잠근 채 읽고 쓴다. */
final class ChildRunRecording {
    final AgentExecution execution;
    int next = 1;
    boolean closed;
    boolean streamEnded;
    Closeable stream;


    ChildRunRecording(AgentExecution execution) {
        this.execution = execution;
    }
}
