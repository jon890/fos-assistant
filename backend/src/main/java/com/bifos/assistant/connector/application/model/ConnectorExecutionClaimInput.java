package com.bifos.assistant.connector.application.model;

import java.util.Map;

/** strict 소비 함수를 통과한 요청이다. 출력과 로그에 원문을 노출하지 않는다. */
public record ConnectorExecutionClaimInput(String ticket, String tool, String argsSha256, Map<String, String> scope) {
    public ConnectorExecutionClaimInput {
        scope = Map.copyOf(scope);
    }

    @Override
    public String toString() {
        return "ConnectorExecutionClaimInput[redacted]";
    }
}
