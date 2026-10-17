package com.bifos.assistant.mcp.application;

import com.bifos.assistant.mcp.application.dto.McpCaller;
import com.bifos.assistant.mcp.application.port.FinancialLimitRequests;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 에이전트가 주문 상한을 읽고 바꾸는 MCP 도구다(ADR-20261015 / financial-user-limits).
 *
 * <p>올리기는 승인 카드만 만들고 반영하지 않는다. 살펴보기 트리에서는 부르지 못한다.
 */
@Service
@RequiredArgsConstructor
public class McpFinancialLimitTools {
    public static final String READ = "order_limit_read";
    public static final String CHANGE = "order_limit_change";
    /** 상한의 API 이름이다. 커넥터 쪽 {@code FinancialLimitKind} 와 같은 목록이다. */
    public static final List<String> NAMES = List.of("order_amount_krw", "order_amount_usd", "order_quantity", "daily_amount_krw",
        "daily_amount_usd");
    /** 0 이상, 소수 6자리까지의 십진 글이다. */
    public static final String DECIMAL = "^[0-9]{1,14}(\\.[0-9]{1,6})?$";
    private final FinancialLimitRequests requests;


    public Map<String, Object> read(McpCaller caller) {
        return requests.read(caller.user());
    }


    public Map<String, Object> change(McpCaller caller, String name, String value) {
        return requests.change(caller.user(), caller.originExecution().id(), name, value);
    }


    public Map<String, Object> readDefinition() {
        return Map.of("name", READ, "description",
            "주문 상한(한 건 원화·달러 금액, 한 건 수량, 하루 원화·달러 합계)의 지금 값과 운영 최대치, 오늘 쓴 금액과 오늘 남은 한도를 읽는다.",
            "inputSchema", object(Map.of(), List.of()));
    }


    public Map<String, Object> changeDefinition() {
        return Map.of("name", CHANGE, "description",
            "주문 상한 하나를 바꾼다. 낮추기는 바로 반영한다. 올리기는 승인 카드를 만들 뿐 반영하지 않는다. 사용자가 카드에서 승인해야 바뀐다. "
                + "최대치를 넘는 값은 거절하고 최대치를 알려 준다. 결과의 status 가 approval_requested 면 아직 바뀌지 않았다고 사용자에게 말한다.",
            "inputSchema", object(Map.of(
                "name", Map.of("type", "string", "enum", NAMES),
                "value", Map.of("type", "string", "pattern", DECIMAL)), List.of("name", "value")));
    }


    private static LinkedHashMap<String, Object> object(Map<String, Object> properties, List<String> required) {
        return new LinkedHashMap<>(Map.of("type", "object", "additionalProperties", false, "properties", properties, "required", required));
    }
}
