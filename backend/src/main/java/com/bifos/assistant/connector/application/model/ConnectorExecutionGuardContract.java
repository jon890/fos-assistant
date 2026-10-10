package com.bifos.assistant.connector.application.model;

import java.util.List;
import java.util.Map;

/** 型とキーを検証した保護宣言だ。宣言の存在だけでは金融呼び出しを開かない。 */
public record ConnectorExecutionGuardContract(
        String protocol, String prepareTool, List<ScopeField> scopeFields, Map<String, String> operations) {
    public ConnectorExecutionGuardContract {
        scopeFields = List.copyOf(scopeFields);
        operations = Map.copyOf(operations);
    }

    public record ScopeField(String arg, String field) {}
}
