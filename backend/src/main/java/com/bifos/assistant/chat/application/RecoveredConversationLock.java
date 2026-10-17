package com.bifos.assistant.chat.application;

import com.bifos.assistant.usage.execution.domain.AgentExecution;
import java.util.ArrayList;
import java.util.List;

/** {@link RestartReconciler}가 잡은 대화 하나의 turn 잠금이다. 바뀌는 값은 {@link RestartReconciler}의 모니터로 지킨다. */
final class RecoveredConversationLock {
    final Long conversationId;

    /** 중지 표시에 붙인 실행 번호다. 그 대화에서 먼저 잡은 줄의 루트 실행이다. */
    final Long executionId;

    final TurnHandle handle;

    /** 이 잠금을 함께 쓰는 줄 가운데 아직 정해지지 않은 수다. */
    int remaining;

    /** 이 잠금을 함께 쓰는 줄의 실행 번호다. 풀 때 취소로 끝난 줄이 있는지 본다. */
    final List<Long> executionIds = new ArrayList<>();


    RecoveredConversationLock(Long conversationId, Long executionId, TurnHandle handle) {
        this.conversationId = conversationId;
        this.executionId = executionId;
        this.handle = handle;
    }


    /** 잡기를 마친 실행만 센다. 호출자는 RestartReconciler의 모니터를 쥔다. */
    void claim(AgentExecution row) {
        remaining++;
        executionIds.add(row.id());
    }
}
