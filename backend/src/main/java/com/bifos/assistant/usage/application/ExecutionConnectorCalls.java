package com.bifos.assistant.usage.application;

import java.time.Instant;

/** 실행 기록의 가림에 필요한 커넥터 호출 이력이다. 위 패키지인 connector 가 구현한다. */
public interface ExecutionConnectorCalls {
    /** 자식과 형제를 포함해 그 시각까지 트리에서 커넥터를 호출했는가. 조회 실패도 참으로 답한다. */
    boolean calledBefore(Long rootId, Instant at);
}
