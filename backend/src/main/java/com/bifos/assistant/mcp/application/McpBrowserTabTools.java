package com.bifos.assistant.mcp.application;

import com.bifos.assistant.browser.application.BrowserAgentControl;
import com.bifos.assistant.browser.application.BrowserTabControl;
import com.bifos.assistant.browser.application.dto.AgentControlStatus;
import com.bifos.assistant.browser.application.dto.BrowserTabs;
import com.bifos.assistant.browser.domain.CdpTarget;
import com.bifos.assistant.mcp.application.dto.McpCaller;
import com.bifos.assistant.mcp.application.port.BrowserTabRequests;
import com.bifos.assistant.shared.util.ToolFailureText;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * 요청자의 「내 브라우저」 탭을 보고, 닫거나 여는 승인 카드를 띄우는 MCP 도구다(docs/features/agent-browser/design.md 의 「PR1 도구」).
 *
 * <p>브라우저는 늘 요청자({@link McpCaller#user()})의 것이고 인자로 사용자나 브라우저를 받지 않는다. 사용자와 관리자의 스위치가 모두 켜져야
 * 쓴다. 닫기와 열기는 카드만 띄우고, 사람이 승인하면 서버가 승인한 인자로 한 번 한다. 실패 글에 탭 제목과 주소를 넣지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpBrowserTabTools {
    public static final String TABS = "browser_tabs";
    public static final String CLOSE = "browser_tab_close";
    public static final String OPEN = "browser_tab_open";
    /** 한 카드로 닫을 탭 수의 상한이다(design DECISION9). */
    public static final int MAX_CLOSE = 20;
    /** 열 주소의 글자 수 상한이다. 카드가 읽을 수 있는 길이다. */
    public static final int MAX_URL = 2000;
    private static final int MAX_TAB_ID = 64;
    private static final String SCREEN = "/browser";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<AgentControlStatus, String> OFF_REASONS = Map.of(
        AgentControlStatus.FEATURE_OFF, "이 서버에서 「내 브라우저」 기능이 꺼져 있다. 브라우저를 다룰 수 없다고 알린다.",
        AgentControlStatus.ADMIN_OFF, "관리자가 에이전트의 브라우저 조작을 꺼 두었다. 관리자에게 부탁하라고 알린다.",
        AgentControlStatus.NO_BROWSER, "사용자의 「내 브라우저」 가 아직 없다. link 의 화면에서 브라우저를 만들고 「에이전트가 쓰게 하기」 를 켜라고 안내한다.",
        AgentControlStatus.USER_OFF, "사용자가 에이전트의 브라우저 조작을 켜지 않았다. link 의 화면에서 「에이전트가 쓰게 하기」 를 켜라고 안내한다.");

    private final BrowserAgentControl control;
    private final BrowserTabControl tabs;
    private final BrowserTabRequests requests;


    /** 탭 목록이다. 꺼져 있으면 까닭과 켤 화면이다. 브라우저가 꺼져 있으면 켜지 않고 빈 목록이다. */
    public Map<String, Object> list(McpCaller caller) {
        Optional<Map<String, Object>> off = off(caller);
        if (off.isPresent()) {
            return off.get();
        }
        try {
            BrowserTabs found = tabs.list(caller.user().id());
            List<Map<String, String>> rows = found.tabs().stream()
                .map(tab -> {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("tab_id", tab.id());
                    row.put("title", tab.title());
                    row.put("url", tab.url());
                    return row;
                })
                .toList();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", found.running() ? "ok" : "browser_stopped");
            body.put("tabs", rows);
            return McpToolService.success(JSON.writeValueAsString(body));
        } catch (RuntimeException ex) {
            log.warn("browser tabs tool failed executionId={} kind={}", caller.executionId(), ex.getClass().getSimpleName());
            return unavailable();
        }
    }


    /** 그 탭들을 닫는 카드를 띄운다. 번호 하나라도 지금 목록에 없으면 카드 없이 거절한다. */
    public Map<String, Object> close(McpCaller caller, List<String> tabIds) {
        Optional<Map<String, Object>> refused = off(caller).or(() -> noConversation(caller));
        if (refused.isPresent()) {
            return refused.get();
        }
        try {
            Map<String, CdpTarget> byId = tabs.list(caller.user().id()).tabs().stream()
                .collect(Collectors.toMap(CdpTarget::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
            List<CdpTarget> chosen = new ArrayList<>();
            for (String id : tabIds) {
                CdpTarget tab = byId.get(id);
                if (tab == null) {
                    return McpToolService.jsonFailure(
                        Map.of("status", "unknown_tab", "message", "지금 목록에 없는 탭 번호가 있다. 카드를 띄우지 않았다."),
                        ToolFailureText.alternative("unknown_tab", "browser_tabs 로 목록을 다시 읽고 그 tab_id 로 다시 부른다"));
                }
                chosen.add(tab);
            }
            return answer(requests.requestClose(caller.user(), caller.originExecution().id(), chosen));
        } catch (RuntimeException ex) {
            log.warn("browser tab close tool failed executionId={} kind={}", caller.executionId(), ex.getClass().getSimpleName());
            return unavailable();
        }
    }


    /** 그 주소로 새 탭을 여는 카드를 띄운다. 공개 웹 주소가 아니면 카드 없이 거절한다. */
    public Map<String, Object> open(McpCaller caller, String url) {
        Optional<Map<String, Object>> refused = off(caller).or(() -> noConversation(caller));
        if (refused.isPresent()) {
            return refused.get();
        }
        try {
            BrowserTabControl.requireWebAddress(url);
        } catch (IllegalArgumentException ex) {
            return McpToolService.jsonFailure(
                Map.of("status", "invalid_url", "message", "http 나 https 의 공개 웹 주소만 열 수 있다. 내부 주소와 파일 주소는 열지 않는다."),
                ToolFailureText.askUser("invalid_url"));
        }
        try {
            return answer(requests.requestOpen(caller.user(), caller.originExecution().id(), url.strip()));
        } catch (RuntimeException ex) {
            log.warn("browser tab open tool failed executionId={} kind={}", caller.executionId(), ex.getClass().getSimpleName());
            return unavailable();
        }
    }


    public List<Map<String, Object>> definitions() {
        return List.of(
            definition(TABS,
                "사용자의 「내 브라우저」 에 열린 탭 목록(tab_id, title, url)을 본다. 사용자가 탭을 정리해 달라거나 열린 페이지를 물을 때 쓴다. "
                    + "다른 작업이 쓰는 탭은 목록에 없다. status 가 browser_stopped 면 브라우저가 꺼져 있어 탭이 없다. "
                    + "isError 면 쓸 수 없는 까닭이다. message 를 알리고 link 가 있으면 그대로 마크다운 링크로 붙인다.",
                Map.of(), List.of()),
            definition(CLOSE,
                "browser_tabs 의 tab_id 로 탭을 닫는 「브라우저 탭 닫기」 승인 카드를 띄운다. 한 번에 " + MAX_CLOSE + "개까지 한 카드로 묶는다. "
                    + "이 도구는 닫지 않는다. 사용자가 카드에서 승인해야 닫히므로 닫았다고 말하지 않고, 승인하면 닫힌다고 알린다. "
                    + "작성 중인 글이 있는 탭일 수 있으니 사용자가 닫으라고 한 탭만 고른다.",
                Map.of("tab_ids", Map.of("type", "array", "minItems", 1, "maxItems", MAX_CLOSE,
                    "items", Map.of("type", "string", "minLength", 1, "maxLength", MAX_TAB_ID))),
                List.of("tab_ids")),
            definition(OPEN,
                "사용자의 「내 브라우저」 에 그 주소로 새 탭을 여는 「브라우저 탭 열기」 승인 카드를 띄운다. http, https 의 공개 웹 주소만 받는다. "
                    + "이 도구는 열지 않는다. 사용자가 카드에서 승인해야 열리므로 열었다고 말하지 않는다. 결제, 로그인, 게시는 사람이 한다.",
                Map.of("url", Map.of("type", "string", "minLength", 1, "maxLength", MAX_URL)),
                List.of("url")));
    }


    private static Map<String, Object> definition(String name, String description, Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return Map.of("name", name, "description", description, "inputSchema", schema);
    }


    private Optional<Map<String, Object>> off(McpCaller caller) {
        AgentControlStatus status = control.status(caller.user());
        if (status.allowed()) {
            return Optional.empty();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.name().toLowerCase(Locale.ROOT));
        body.put("message", OFF_REASONS.get(status));
        if (status == AgentControlStatus.NO_BROWSER || status == AgentControlStatus.USER_OFF) {
            body.put("link", SCREEN);
        }
        return Optional.of(McpToolService.jsonFailure(body, ToolFailureText.askUser(status.name().toLowerCase(Locale.ROOT))));
    }


    private static Optional<Map<String, Object>> noConversation(McpCaller caller) {
        if (caller.originExecution().conversationId() != null) {
            return Optional.empty();
        }
        return Optional.of(McpToolService.jsonFailure(
            Map.of("status", "no_conversation", "message", "카드를 보일 대화가 없는 실행이라 카드를 띄우지 않았다."),
            ToolFailureText.askUser("no_conversation")));
    }


    private static Map<String, Object> answer(Map<String, Object> body) {
        if (BrowserTabRequests.REQUESTED.equals(body.get("status"))) {
            return McpToolService.success(JSON.writeValueAsString(body));
        }
        return McpToolService.jsonFailure(body, ToolFailureText.askUser(String.valueOf(body.get("status"))));
    }


    private static Map<String, Object> unavailable() {
        return McpToolService.jsonFailure(
            Map.of("status", "unavailable", "message", "지금 「내 브라우저」 에 닿지 못했다. 했다고 말하지 않고 잠시 뒤 다시 해 보겠다고 알린다."),
            ToolFailureText.alternative("unavailable", "잠시 뒤 한 번 더 부르고, 또 실패하면 사용자에게 알린다"));
    }
}
