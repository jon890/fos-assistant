package com.bifos.assistant.connector.application.execution;

import com.bifos.assistant.connector.application.ConnectorExecutionSnapshot;
import com.bifos.assistant.connector.application.ConnectorToolPolicies;
import com.bifos.assistant.connector.application.model.ConnectorExecutionGuardContract;
import com.bifos.assistant.connector.application.model.ConnectorExecutionGuardContract.ScopeField;
import com.bifos.assistant.connector.domain.type.ToolApproval;
import com.bifos.assistant.connector.domain.type.ToolRisk;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 카탈로그의 최소 보호 선언을 기본값과 타입 보충 없이 읽는다. */
@Component
public class ConnectorExecutionGuard {
    public ConnectorExecutionGuardContract read(ConnectorManifest manifest) {
        JsonNode node = manifest.executionGuard();
        require(node != null
                && node.isObject()
                && node.propertyNames().equals(Set.of("protocol", "prepare_tool", "scope_fields", "operations")));
        String protocol = text(node.get("protocol"));
        String prepare = text(node.get("prepare_tool"));
        require(ConnectorExecutionSnapshot.PROTOCOL.equals(protocol));
        require(ConnectorToolPolicies.find(manifest, prepare)
                .filter(p -> p.risk() == ToolRisk.READ && p.approval() == ToolApproval.NONE)
                .isPresent());
        JsonNode operations = node.get("operations");
        require(operations.isObject() && !operations.isEmpty() && operations.size() <= 8);
        var declared = new LinkedHashMap<String, String>();
        for (var entry : operations.properties()) {
            String operation = text(entry.getValue());
            require(Set.of("CREATE", "MODIFY", "CANCEL").contains(operation) && !prepare.equals(entry.getKey()));
            require(ConnectorToolPolicies.find(manifest, entry.getKey())
                    .filter(p -> p.risk() == ToolRisk.FINANCIAL && p.approval() == ToolApproval.ALWAYS)
                    .isPresent());
            declared.put(entry.getKey(), operation);
        }
        JsonNode fields = node.get("scope_fields");
        require(fields.isArray() && !fields.isEmpty() && fields.size() <= 8);
        var scopes = new ArrayList<ScopeField>();
        var args = new HashSet<String>();
        var names = new HashSet<String>();
        for (JsonNode field : fields) {
            require(field.isObject() && field.propertyNames().equals(Set.of("arg", "field")));
            String arg = text(field.get("arg"));
            String name = text(field.get("field"));
            require(arg.matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                    && name.matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                    && args.add(arg)
                    && names.add(name));
            require(manifest.fields().stream().anyMatch(f -> name.equals(f.key()) && !f.secret()));
            scopes.add(new ScopeField(arg, name));
        }
        return new ConnectorExecutionGuardContract(protocol, prepare, scopes, declared);
    }

    private static String text(JsonNode node) {
        require(node != null && node.isString() && !node.stringValue().isBlank());
        return node.stringValue();
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("financial execution unavailable");
        }
    }
}
