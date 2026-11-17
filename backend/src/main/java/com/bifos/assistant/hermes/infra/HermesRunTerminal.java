package com.bifos.assistant.hermes.infra;

import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * 끝 사건 하나다.
 *
 * @param event Hermes 의 끝 사건 이름({@code run.completed} 등)
 * @param pendingSteerPresent 사건에 {@code pending_steer} 칸이 있었는가. 없으면 넘어간 글이 없다는 뜻이다. 정상 종료로 끊긴
 * {@code run.interrupted} 는 칸을 버리므로 이 값으로 판단하지 않는다
 */
public record HermesRunTerminal(
    String event,
    String error,
    String pendingSteer,
    boolean pendingSteerPresent,
    String servedModel,
    String servedProvider) {


    /** Hermes 의 끝 사건 이름이다. */
    static final Set<String> TERMINAL_EVENTS = Set.of("run.completed", "run.failed", "run.cancelled", "run.interrupted");

    /** 끝 사건이면 {@link #of} 로 옮기고, 아니면 빈 값이다. 사건 스트림이 받은 사건마다 부른다. */
    public static Optional<HermesRunTerminal> ofTerminalEvent(String event, JsonNode root) {
        return TERMINAL_EVENTS.contains(event) ? Optional.of(of(event, root)) : Optional.empty();
    }


    /** 끝 사건 JSON 에서 실행 조회가 v0.23.2 에서 주지 않는 값을 꺼낸다. 칸은 최상위나 {@code data} 아래에 온다. */
    static HermesRunTerminal of(String event, JsonNode root) {
        JsonNode payload = root.path("data");
        JsonNode pending = root.has("pending_steer") ? root.get("pending_steer") : payload.get("pending_steer");
        JsonNode runtime = root.has("runtime") ? root.path("runtime") : payload.path("runtime");
        String error = text(root, "error");
        String pendingText = pending == null || pending.isNull() ? null : pending.asText();
        return new HermesRunTerminal(
            event,
            error == null ? text(payload, "error") : error,
            pendingText == null || pendingText.isBlank() ? null : pendingText,
            pending != null,
            text(runtime, "model"),
            text(runtime, "provider"));
    }


    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }


    public boolean interrupted() {
        return "run.interrupted".equals(event);
    }


    public boolean cancelled() {
        return "run.cancelled".equals(event);
    }
}
