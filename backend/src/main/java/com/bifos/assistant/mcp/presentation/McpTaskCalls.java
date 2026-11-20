package com.bifos.assistant.mcp.presentation;

import static com.bifos.assistant.mcp.presentation.McpRpc.INVALID_ARGUMENTS;
import static com.bifos.assistant.mcp.presentation.McpRpc.invalidParams;
import static com.bifos.assistant.mcp.presentation.McpRpc.response;

import com.bifos.assistant.mcp.application.McpTaskTools;
import com.bifos.assistant.mcp.application.dto.McpCaller;
import com.bifos.assistant.mcp.application.dto.TaskToolInput;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 예약 작업 도구의 인자 모양을 확인하고 {@link McpTaskTools} 로 넘긴다. 스키마를 벗어난 인자는 다른 도구처럼 JSON-RPC 인자 오류다.
 * 값의 뜻(cron 이 맞는지, 작업이 있는지)은 도구 결과의 실패로 답한다.
 */
@Component
@RequiredArgsConstructor
public class McpTaskCalls {
    private static final Set<String> CREATE_FIELDS = Set.of("title", "instruction", "cron", "fire_at", "notify");
    private static final Set<String> UPDATE_FIELDS = Set.of("task", "title", "instruction", "cron", "fire_at", "notify");
    private static final Set<String> STATE_FIELDS = Set.of("task", "state");
    private static final Set<String> DELETE_FIELDS = Set.of("task");
    private final McpTaskTools tools;


    Map<String, Object> list(McpCaller caller, JsonNode id, JsonNode arguments) {
        if (!arguments.propertyNames().isEmpty()) {
            return invalidParams(id, INVALID_ARGUMENTS);
        }
        return response(id, tools.list(caller));
    }


    Map<String, Object> create(McpCaller caller, JsonNode id, JsonNode arguments) {
        Optional<TaskToolInput> input = input(arguments, CREATE_FIELDS);
        if (input.isEmpty() || input.get().title() == null || input.get().instruction() == null
            || (input.get().cron() == null) == (input.get().fireAt() == null)) {
            return invalidParams(id, INVALID_ARGUMENTS);
        }
        return response(id, tools.create(caller, input.get()));
    }


    Map<String, Object> update(McpCaller caller, JsonNode id, JsonNode arguments) {
        Optional<TaskToolInput> input = input(arguments, UPDATE_FIELDS);
        Optional<String> task = text(arguments, "task", Integer.MAX_VALUE);
        if (input.isEmpty() || task.isEmpty() || input.get().empty()
            || (input.get().cron() != null && input.get().fireAt() != null)) {
            return invalidParams(id, INVALID_ARGUMENTS);
        }
        return response(id, tools.update(caller, task.get(), input.get()));
    }


    Map<String, Object> setState(McpCaller caller, JsonNode id, JsonNode arguments) {
        Optional<String> task = text(arguments, "task", Integer.MAX_VALUE);
        Optional<String> state = text(arguments, "state", Integer.MAX_VALUE);
        if (!fieldsWithin(arguments, STATE_FIELDS) || task.isEmpty() || state.isEmpty()
            || !McpTaskTools.STATES.contains(state.get())) {
            return invalidParams(id, INVALID_ARGUMENTS);
        }
        return response(id, tools.setState(caller, task.get(), state.get()));
    }


    Map<String, Object> delete(McpCaller caller, JsonNode id, JsonNode arguments) {
        Optional<String> task = text(arguments, "task", Integer.MAX_VALUE);
        if (!fieldsWithin(arguments, DELETE_FIELDS) || task.isEmpty()) {
            return invalidParams(id, INVALID_ARGUMENTS);
        }
        return response(id, tools.delete(caller, task.get()));
    }


    /** 받은 칸을 읽는다. 모르는 칸이 있거나 글이 아니거나 비었거나 너무 길면 빈 값이다. 없는 칸은 null 이다. */
    private static Optional<TaskToolInput> input(JsonNode arguments, Set<String> allowed) {
        if (!fieldsWithin(arguments, allowed)) {
            return Optional.empty();
        }
        String[] values = new String[5];
        String[] names = {"title", "instruction", "cron", "fire_at", "notify"};
        int[] limits = {McpTaskTools.TITLE_MAX, McpTaskTools.INSTRUCTION_MAX, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        for (int i = 0; i < names.length; i++) {
            if (arguments.get(names[i]) == null) {
                continue;
            }
            Optional<String> value = text(arguments, names[i], limits[i]);
            if (value.isEmpty()) {
                return Optional.empty();
            }
            values[i] = value.get();
        }
        if (values[4] != null && !McpTaskTools.NOTIFY.contains(values[4])) {
            return Optional.empty();
        }
        return Optional.of(new TaskToolInput(values[0], values[1], values[2], values[3], values[4]));
    }


    private static boolean fieldsWithin(JsonNode arguments, Set<String> allowed) {
        return allowed.containsAll(arguments.propertyNames());
    }


    /** 비지 않은 글이고 앞뒤 공백을 뗀 길이가 상한 안이면 그 글이다. */
    private static Optional<String> text(JsonNode arguments, String name, int max) {
        JsonNode node = arguments.get(name);
        if (node == null || !node.isString()) {
            return Optional.empty();
        }
        String value = node.stringValue().strip();
        if (value.isEmpty() || value.codePointCount(0, value.length()) > max) {
            return Optional.empty();
        }
        return Optional.of(value);
    }
}
