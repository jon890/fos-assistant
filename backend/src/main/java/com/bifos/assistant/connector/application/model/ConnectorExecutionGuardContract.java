package com.bifos.assistant.connector.application.model;

import com.bifos.assistant.connector.application.ConnectorExecutionSnapshot;
import java.util.List;
import java.util.Map;

/**
 * 타입과 키를 검증한 보호 선언이다. 선언이 있다는 이유만으로 금융 호출을 허용하지 않는다.
 *
 * @param kinds 도구가 받는 summary 종류. 선언하지 않은 도구는 일반 주문이다
 */
public record ConnectorExecutionGuardContract(
    String protocol,
    String prepareTool,
    List<ScopeField> scopeFields,
    Map<String, String> operations,
    Map<String, String> kinds) {
    public ConnectorExecutionGuardContract {
        scopeFields = List.copyOf(scopeFields);
        operations = Map.copyOf(operations);
        kinds = Map.copyOf(kinds);
    }


    /** 검증 분기는 prepare 가 낸 summary 가 아니라 이 값이 정한다. */
    public String kind(String tool) {
        return kinds.getOrDefault(tool, ConnectorExecutionSnapshot.ORDER);
    }


    public record ScopeField(String arg, String field) {
    }
}
