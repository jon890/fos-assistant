package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.usage.execution.domain.AgentExecution;
import java.io.Closeable;

/** {@link ChildRunEvents} 가 실행 하나를 기록하는 상태다. 필드는 이 객체를 잠근 채 읽고 쓴다. */
final class ChildRunRecording {
    final AgentExecution execution;
    int next = 1;
    boolean closed;
    boolean streamEnded;
    /** 스트림이 끝 사건을 받았다. 실행이 먼저 끝나 스트림을 닫아도 다 받았으면 관측 완료로 본다 */
    boolean terminalSeen;
    Closeable stream;


    ChildRunRecording(AgentExecution execution) {
        this.execution = execution;
    }
}
