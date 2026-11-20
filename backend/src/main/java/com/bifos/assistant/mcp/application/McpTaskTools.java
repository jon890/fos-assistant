package com.bifos.assistant.mcp.application;

import com.bifos.assistant.mcp.application.dto.McpCaller;
import com.bifos.assistant.mcp.application.dto.TaskToolInput;
import com.bifos.assistant.mcp.application.dto.TaskToolResult;
import com.bifos.assistant.mcp.application.port.TaskRequests;
import com.bifos.assistant.shared.util.ToolFailureText;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * 에이전트가 대화에서 요청자의 예약 작업을 다루는 MCP 도구다(스펙 service-concepts REQ7-REQ10).
 *
 * <p>만들기, 고치기, 멈추기와 다시 켜기는 바로 한다. 지우기는 승인 카드만 띄운다. 언제 어떻게 쓰는지는 도구 설명이 갖는다(스펙
 * DECISION11). 결과는 다른 도구처럼 글 하나로 감싸고, 하지 못했으면 {@code isError} 와 까닭이다. 예외가 나도 turn 을 막지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpTaskTools {
    public static final String LIST = "task_list";
    public static final String CREATE = "task_create";
    public static final String UPDATE = "task_update";
    public static final String SET_STATE = "task_set_state";
    public static final String DELETE = "task_delete";
    public static final List<String> NOTIFY = List.of("always", "on_failure", "never");
    public static final List<String> STATES = List.of("paused", "active");
    /** 이름 글자 수 상한이다. 화면의 작업 이름과 같다. */
    public static final int TITLE_MAX = 100;
    /** 지시문 글자 수 상한이다. 화면의 작업 지시와 같다. */
    public static final int INSTRUCTION_MAX = 8000;
    static final String UNAVAILABLE = "예약 작업을 지금 다루지 못했다. 했다고 말하지 않고, 잠시 뒤 다시 부탁하거나 /tasks 화면에서 하라고 안내한다.";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String FAILURE_RULE = "isError 면 하지 못한 것이다. 했다고 말하지 않고 message 의 까닭을 알린다.";
    private static final String TASK_ARG = "task_list 의 id 나 작업 이름. 이름이 같은 작업이 여럿이면 status ambiguous 와 후보만 돌려주고 "
        + "바꾸지 않으므로 사용자에게 어느 것인지 되묻는다.";
    private final TaskRequests requests;


    public Map<String, Object> list(McpCaller caller) {
        return guarded(caller, () -> requests.list(caller.user()));
    }


    public Map<String, Object> create(McpCaller caller, TaskToolInput input) {
        return guarded(caller, () -> requests.create(caller.user(), caller.originExecution().id(), input));
    }


    public Map<String, Object> update(McpCaller caller, String task, TaskToolInput changes) {
        return guarded(caller, () -> requests.update(caller.user(), task, changes));
    }


    public Map<String, Object> setState(McpCaller caller, String task, String state) {
        return guarded(caller, () -> requests.setPaused(caller.user(), task, "paused".equals(state)));
    }


    public Map<String, Object> delete(McpCaller caller, String task) {
        return guarded(caller, () -> requests.requestDelete(caller.user(), caller.originExecution().id(), task));
    }


    public List<Map<String, Object>> definitions() {
        return List.of(
            definition(LIST,
                "요청자의 예약 작업 목록을 읽는다. 작업마다 id, title, state(ACTIVE 켜짐, PAUSED 멈춤), schedule, next_run, notify, link 를 준다. "
                    + "에이전트의 매일 깨우기는 없다. 매일 깨우기를 바꿔 달라고 하면 그 에이전트 화면 /agents/<code> 링크로 안내한다. " + FAILURE_RULE,
                Map.of(), List.of()),
            definition(CREATE,
                "사용자가 정한 시각이나 주기마다 일을 해 달라고 하면(「매일 아침 7시에 날씨 알려 줘」, 「매시간 확인해서 알려 줘」) 예약 작업을 바로 만든다. "
                    + "시각이나 반복이 모호하면 만들지 않고 되묻는다. 시각은 Asia/Seoul 기준이고 cron(표준 5필드, 최소 15분 간격)이나 "
                    + "fire_at(한 번, 2026-11-21T07:00 꼴) 가운데 하나를 준다. 하루 발화가 너무 많은 주기는 거절된다. "
                    + "instruction 은 사용자가 한 말을 그대로 넣지 말고 대화 맥락으로 구체적으로 쓴다. 대상의 정확한 조건과 제외할 것, 확인 방법"
                    + "(검증하지 못한 후보는 빼기), 판단 기준, 알릴 때 담을 것, 이미 알린 것은 되풀이하지 않기, 새것이 없으면 알리지 않기, 사용자 상황을 담는다. "
                    + "작업은 이 에이전트로 돌고 결과는 이 대화에 이어 쌓인다. 모델 단계는 사용자가 작업 화면에서 고른다. "
                    + "만든 뒤 답에 작업 이름, next_run, 지시문 요약, link 를 마크다운 링크로 적는다. " + FAILURE_RULE,
                Map.of(
                    "title", Map.of("type", "string", "minLength", 1, "maxLength", TITLE_MAX),
                    "instruction", Map.of("type", "string", "minLength", 1, "maxLength", INSTRUCTION_MAX),
                    "cron", Map.of("type", "string"),
                    "fire_at", Map.of("type", "string"),
                    "notify", Map.of("type", "string", "enum", NOTIFY)),
                List.of("title", "instruction")),
            definition(UPDATE,
                "사용자가 예약 작업의 이름, 할 일, 시각과 반복, 알림을 바꿔 달라고 하면 바로 고친다. 준 칸만 바뀐다. "
                    + "에이전트, 모델 단계, 시간대, 대화 방식은 고치지 못하므로 /tasks/<id> 화면에서 바꾸라고 안내한다. "
                    + "instruction 을 고칠 때도 만들 때처럼 구체적으로 쓴다. " + FAILURE_RULE,
                Map.of(
                    "task", Map.of("type", "string", "description", TASK_ARG),
                    "title", Map.of("type", "string", "minLength", 1, "maxLength", TITLE_MAX),
                    "instruction", Map.of("type", "string", "minLength", 1, "maxLength", INSTRUCTION_MAX),
                    "cron", Map.of("type", "string"),
                    "fire_at", Map.of("type", "string"),
                    "notify", Map.of("type", "string", "enum", NOTIFY)),
                List.of("task")),
            definition(SET_STATE,
                "사용자가 예약 작업을 멈추거나(paused) 다시 켜 달라고(active) 하면 바로 한다. 다시 켜면 다음 시각부터 돈다. " + FAILURE_RULE,
                Map.of(
                    "task", Map.of("type", "string", "description", TASK_ARG),
                    "state", Map.of("type", "string", "enum", STATES)),
                List.of("task", "state")),
            definition(DELETE,
                "사용자가 예약 작업을 지워 달라고 하면 지우기 승인 카드를 띄운다. 이 도구는 지우지 않는다. 사용자가 카드에서 승인해야 지워지므로 "
                    + "지웠다고 말하지 않고 승인 카드에서 승인하면 지워진다고 알린다. " + FAILURE_RULE,
                Map.of("task", Map.of("type", "string", "description", TASK_ARG)),
                List.of("task")));
    }


    private Map<String, Object> guarded(McpCaller caller, Supplier<TaskToolResult> action) {
        try {
            TaskToolResult result = action.get();
            if (!result.error()) {
                return McpToolService.success(JSON.writeValueAsString(result.body()));
            }
            return McpToolService.jsonFailure(result.body(), nextFor(String.valueOf(result.body().get("status"))));
        } catch (RuntimeException ex) {
            log.warn("task tool failed executionId={} kind={}", caller.executionId(), ex.getClass().getSimpleName());
            return McpToolService.jsonFailure(Map.of("status", "unavailable", "message", UNAVAILABLE), nextFor("unavailable"));
        }
    }


    /** 예약 작업 도구의 실패마다 모델이 해 볼 다음 방법이다(docs/features/tool-fallback/design.md 의 「바꾸는 실패 글」). */
    static ToolFailureText nextFor(String status) {
        return switch (status) {
            case "invalid", "invalid_schedule" -> ToolFailureText.alternative(status, "인자를 도구 설명의 형식에 맞게 고쳐 다시 부른다");
            case "not_found" -> ToolFailureText.alternative(status, "task_list 로 이름을 확인해 다시 부르고, 없으면 사용자에게 되묻는다");
            case "unavailable" -> ToolFailureText.alternative(status, "다른 방법이 없으니 /tasks 화면에서 하라고 안내하고 잠시 뒤 다시 해 달라고 알린다");
            default -> ToolFailureText.askUser(status);
        };
    }


    private static Map<String, Object> definition(
        String name, String description, Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("properties", properties);
        schema.put("required", required);
        return Map.of("name", name, "description", description, "inputSchema", schema);
    }
}
