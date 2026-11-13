package com.bifos.assistant.connector.financial.application;

import com.bifos.assistant.connector.connection.application.ConnectorToolPolicies;
import com.bifos.assistant.connector.financial.application.dto.ConnectorExecutionGuardContract;
import com.bifos.assistant.connector.financial.application.dto.ConnectorExecutionGuardContract.ScopeField;
import com.bifos.assistant.connector.connection.domain.type.ToolApproval;
import com.bifos.assistant.connector.connection.domain.type.ToolRisk;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
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
        require(node != null && node.isObject());
        var keys = new HashSet<>(node.propertyNames());
        keys.remove("kinds");
        require(keys.equals(Set.of("protocol", "prepare_tool", "scope_fields", "operations")));
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
            declared.put(entry.getKey(), operation);
        }
        var kinds = kinds(node, declared);
        // 주문서 준비 도구는 PURCHASE, 나머지는 FINANCIAL 이다. 한 선언에 두 위험도를 섞지 않는다(ADR-20261113 / purchase-checkout).
        boolean checkout = kinds.containsValue(ConnectorExecutionSnapshot.CHECKOUT);
        for (var entry : declared.entrySet()) {
            boolean purchase = ConnectorExecutionSnapshot.CHECKOUT.equals(kinds.get(entry.getKey()));
            require(purchase == checkout && (!purchase || "CREATE".equals(entry.getValue())));
            ToolRisk risk = purchase ? ToolRisk.PURCHASE : ToolRisk.FINANCIAL;
            require(ConnectorToolPolicies.find(manifest, entry.getKey())
                .filter(p -> p.risk() == risk && p.approval() == ToolApproval.ALWAYS)
                .isPresent());
        }
        JsonNode fields = node.get("scope_fields");
        // 쇼핑 연결에는 계좌 같은 공개 칸이 없어 주문서 준비의 scope 는 비어 있다.
        require(fields.isArray() && fields.isEmpty() == checkout && fields.size() <= 8);
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
        return new ConnectorExecutionGuardContract(protocol, prepare, scopes, declared, kinds);
    }


    private static LinkedHashMap<String, String> kinds(JsonNode node, LinkedHashMap<String, String> declared) {
        var kinds = new LinkedHashMap<String, String>();
        if (node.has("kinds")) {
            JsonNode declaredKinds = node.get("kinds");
            require(declaredKinds.isObject() && !declaredKinds.isEmpty());
            for (var entry : declaredKinds.properties()) {
                String kind = text(entry.getValue());
                require(declared.containsKey(entry.getKey()) && Set.of(ConnectorExecutionSnapshot.ORDER,
                    ConnectorExecutionSnapshot.CONDITIONAL, ConnectorExecutionSnapshot.CHECKOUT).contains(kind));
                kinds.put(entry.getKey(), kind);
            }
        }
        return kinds;
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
