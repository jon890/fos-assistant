package com.bifos.assistant.connector.application.model;

import java.util.List;
import java.util.Map;

/** 타입과 키를 검증한 보호 선언이다. 선언이 있다는 이유만으로 금융 호출을 허용하지 않는다. */
public record ConnectorExecutionGuardContract(
        String protocol, String prepareTool, List<ScopeField> scopeFields, Map<String, String> operations) {
    public ConnectorExecutionGuardContract {
        scopeFields = List.copyOf(scopeFields);
        operations = Map.copyOf(operations);
    }

    public record ScopeField(String arg, String field) {}
}
