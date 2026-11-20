package com.bifos.assistant.mcp.application;

import com.bifos.assistant.mcp.application.dto.McpCaller;
import com.bifos.assistant.mcp.application.port.FinancialLimitRequests;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import java.util.Set;
import com.bifos.assistant.shared.util.ToolFailureText;

/**
 * 에이전트가 주문 상한을 읽고 바꾸는 MCP 도구다(ADR-20261015 / financial-user-limits).
 *
 * <p>올리기는 승인 카드만 만들고 반영하지 않는다. 살펴보기 트리에서는 부르지 못한다.
 *
 * <p>결과는 다른 도구처럼 {@code content} 의 글 하나로 감싼다. Hermes 는 MCP {@code CallToolResult} 모양이 아닌 결과를 검증 오류로
 * 버리므로 값 객체를 그대로 내면 모델은 도구 실패만 받는다.
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
    /** 상한을 바꾸지 못한 상태다. */
    private static final Set<String> UNCHANGED = Set.of("unavailable", "above_maximum", "no_financial_connection", "closed");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final FinancialLimitRequests requests;


    public Map<String, Object> read(McpCaller caller) {
        return McpToolService.success(JSON.writeValueAsString(requests.read(caller.user())));
    }


    public Map<String, Object> change(McpCaller caller, String name, String value) {
        return McpToolService.success(JSON.writeValueAsString(withNext(requests.change(caller.user(), caller.originExecution().id(), name, value))));
    }


    /**
     * 바꾸지 못한 결과에 바로 알림 끝 줄을 {@code next} 칸으로 더한다. 결제 상한과 승인, 연결은 사용자가 정한다
     * (docs/features/tool-fallback/spec.md 의 REQ2, REQ3). {@code isError} 는 지금처럼 거짓이다.
     */
    static Map<String, Object> withNext(Map<String, Object> result) {
        Object status = result.get("status");
        if (!UNCHANGED.contains(status)) {
            return result;
        }
        Map<String, Object> body = new LinkedHashMap<>(result);
        body.put("next", ToolFailureText.askUser(String.valueOf(status)).endLine());
        return body;
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
