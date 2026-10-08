package com.bifos.assistant.connector.application;

import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.usage.application.ExecutionConnectorCalls;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 도구 내용 가림에서 커넥터 호출의 원문을 읽지 않고 트리의 호출 여부만 확인한다. */
@Service
@Slf4j
@RequiredArgsConstructor
public class ConnectorCallHistory implements ExecutionConnectorCalls {
    private final ConnectorActionRepository actions;

    @Override
    public boolean calledBefore(Long rootId, Instant at) {
        try {
            return actions.existsCallInTreeBefore(rootId, at);
        } catch (RuntimeException ex) {
            // 이력 확인 실패가 실행 기록의 원문 공개로 이어지지 않게 한다.
            log.warn("커넥터 호출 이력을 확인하지 못해 도구 내용을 가린다 rootId={}", rootId);
            return true;
        }
    }
}
