package com.bifos.assistant.mcp.presentation;

import static com.bifos.assistant.mcp.presentation.McpRpc.INVALID_ARGUMENTS;
import static com.bifos.assistant.mcp.presentation.McpRpc.invalidParams;
import static com.bifos.assistant.mcp.presentation.McpRpc.response;

import com.bifos.assistant.mcp.application.McpBrowserTabTools;
import com.bifos.assistant.mcp.application.dto.McpCaller;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 「내 브라우저」 탭 도구의 인자 모양을 확인하고 {@link McpBrowserTabTools} 로 넘긴다. 스키마를 벗어난 인자는 다른 도구처럼 JSON-RPC 인자
 * 오류다. 값의 뜻(목록에 있는 탭인지, 공개 웹 주소인지)은 도구 결과의 실패로 답한다.
 */
@Component
@RequiredArgsConstructor
public class McpBrowserTabCalls {
    static final String TABS = McpBrowserTabTools.TABS;
    static final String CLOSE = McpBrowserTabTools.CLOSE;
    static final String OPEN = McpBrowserTabTools.OPEN;
    /**
     * 먼저 살펴보기 트리에서도 쓰기 설정과 관계없이 받는 도구다. 목록은 읽기이고 닫기와 열기는 카드만 띄운다
     * (ADR-20261128 / agent-browser).
     */
    static final Set<String> NAMES = Set.of(TABS, CLOSE, OPEN);
    private static final int MAX_TAB_ID = 64;
    private final McpBrowserTabTools tools;


    Map<String, Object> tabs(McpCaller caller, JsonNode id, JsonNode arguments) {
        if (arguments != null && !arguments.isEmpty()) {
            return invalidParams(id, INVALID_ARGUMENTS);
        }
        return response(id, tools.list(caller));
    }


    Map<String, Object> close(McpCaller caller, JsonNode id, JsonNode arguments) {
        return tabIds(arguments).map(ids -> response(id, tools.close(caller, ids))).orElseGet(() -> invalidParams(id, INVALID_ARGUMENTS));
    }


    Map<String, Object> open(McpCaller caller, JsonNode id, JsonNode arguments) {
        return url(arguments).map(url -> response(id, tools.open(caller, url))).orElseGet(() -> invalidParams(id, INVALID_ARGUMENTS));
    }


    /** {@code tab_ids} 는 1개에서 {@link McpBrowserTabTools#MAX_CLOSE} 개의 비지 않은 글이다. 다른 키가 있으면 빈 값이다. */
    static Optional<List<String>> tabIds(JsonNode arguments) {
        JsonNode value = arguments.get("tab_ids");
        if (arguments.size() != 1 || value == null || !value.isArray() || value.isEmpty() || value.size() > McpBrowserTabTools.MAX_CLOSE) {
            return Optional.empty();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode item : value) {
            if (!item.isString() || item.asString().isBlank() || item.asString().length() > MAX_TAB_ID) {
                return Optional.empty();
            }
            ids.add(item.asString().strip());
        }
        return Optional.of(List.copyOf(ids));
    }


    /** {@code url} 은 {@link McpBrowserTabTools#MAX_URL} 자까지의 비지 않은 글 하나다. */
    static Optional<String> url(JsonNode arguments) {
        JsonNode value = arguments.get("url");
        if (arguments.size() != 1 || value == null || !value.isString()) {
            return Optional.empty();
        }
        String url = value.asString().strip();
        return url.isEmpty() || url.length() > McpBrowserTabTools.MAX_URL ? Optional.empty() : Optional.of(url);
    }
}
