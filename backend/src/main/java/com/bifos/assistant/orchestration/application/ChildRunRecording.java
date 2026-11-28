package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.usage.execution.domain.AgentExecution;
import java.io.Closeable;
import java.util.HashMap;
import java.util.Map;

/** {@link ChildRunEvents} 가 실행 하나를 기록하는 상태다. 필드는 이 객체를 잠근 채 읽고 쓴다. */
final class ChildRunRecording {
    final AgentExecution execution;
    int next = 1;
    boolean closed;
    boolean streamEnded;
    boolean terminalSeen;
    Closeable stream;
    Thread streamThread;
    /** 결과 확정 뒤 도구 완료를 계속 받는 동안만 값이 있다. */
    Long drainDeadline;
    boolean drainIncomplete;
    /** 호출 번호마다 남은 시작의 수다. 0 이면 완료를 이미 받았다. */
    final Map<String, Integer> pendingTools = new HashMap<>();


    ChildRunRecording(AgentExecution execution) {
        this.execution = execution;
    }
}
