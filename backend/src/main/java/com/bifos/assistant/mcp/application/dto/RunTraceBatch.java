package com.bifos.assistant.mcp.application.dto;

import com.bifos.assistant.orchestration.application.dto.ToolTraceRecord;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

/**
 * profile 플러그인이 보낸 실행 기록 묶음에서 서명을 확인한 기록만 꺼낸다.
 *
 * <p>계약은 {@code hermes/plugins/fos-ctx/README.md} 의 「도구 기록을 보낸다」 가 갖는다. 서명은 {@link McpCallContext} 와 같은
 * HMAC-SHA256 이고 key 도 같다. 서명할 글은 {@code v1-run-trace}, {@code kind}, 루트 session, session, {@code tool_call_id} 를 이
 * 순서로 {@code \n} 하나로 이은 UTF-8 이다. 첫 줄이 다른 내부 경로와 달라 서명이 서로 쓰이지 않는다.
 *
 * <p>기록 하나가 틀려도 나머지는 받는다. 버린 까닭은 칸 이름만 로그에 남기고 본문, 토큰 해시, 서명 값은 적지 않는다. 오류 글은 원문
 * 그대로 넘기므로 이 클래스와 받은 값의 {@code toString} 에 싣지 않는다(스펙 run-trace REQ6).
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RunTraceBatch {

    /** 한 요청에서 읽는 기록의 수다. 넘는 기록은 읽지 않는다. */
    public static final int MAX_RECORDS = 100;

    /** 오류 글로 받는 길이다. 가림 규칙의 입력 상한보다 한 글자 길게 받아, 넘은 글은 가림 규칙이 통째로 가리게 한다. */
    static final int MAX_ERROR_MESSAGE = 65_537;

    private static final String VERSION_LINE = "v1-run-trace";
    private static final String TOOL_KIND = "tool";
    private static final int MAX_NAME = 256;


    /**
     * @param body 요청 본문을 읽은 JSON
     * @param tokenHash 요청을 인증한 토큰의 SHA-256 소문자 16진수
     * @return 서명을 확인한 도구 기록. 묶음 모양이 틀리면 빈 목록이다
     */
    public static List<ToolTraceRecord> verifiedTools(JsonNode body, String tokenHash) {
        List<ToolTraceRecord> records = new ArrayList<>();
        if (tokenHash == null || tokenHash.isBlank() || body == null || !body.isObject()
            || !McpCallContext.isVersionOne(body.get("v"))) {
            log.warn("실행 기록 묶음을 거절했다 reason=묶음 모양");
            return records;
        }
        JsonNode items = body.get("records");
        if (items == null || !items.isArray()) {
            log.warn("실행 기록 묶음을 거절했다 reason=records");
            return records;
        }
        int rejected = 0;
        for (int i = 0; i < Math.min(items.size(), MAX_RECORDS); i++) {
            ToolTraceRecord record = tool(items.get(i), tokenHash);
            if (record == null) {
                rejected++;
            } else {
                records.add(record);
            }
        }
        if (rejected > 0 || items.size() > MAX_RECORDS) {
            log.warn("실행 기록 일부를 버렸다 rejected={} overflow={}", rejected, Math.max(0, items.size() - MAX_RECORDS));
        }
        return records;
    }


    private static ToolTraceRecord tool(JsonNode item, String tokenHash) {
        if (item == null || !item.isObject() || !TOOL_KIND.equals(text(item, "kind"))) {
            return null;
        }
        String root = text(item, "root_session_id");
        String session = text(item, "session_id");
        String toolCallId = item.has("tool_call_id") ? textOrEmpty(item, "tool_call_id") : null;
        String signature = text(item, "sig");
        if (blank(root) || blank(session) || toolCallId == null || signature == null
            || !McpCallContext.SIGNATURE.matcher(signature).matches()) {
            return null;
        }
        byte[] expected = McpCallContext.hmac(tokenHash, String.join("\n", VERSION_LINE, TOOL_KIND, root, session, toolCallId));
        if (!MessageDigest.isEqual(expected, HexFormat.of().parseHex(signature))) {
            return null;
        }
        String toolName = text(item, "tool_name");
        if (toolName != null && toolName.length() > MAX_NAME) {
            return null;
        }
        String status = text(item, "status");
        String errorMessage = text(item, "error_message");
        if (errorMessage != null && errorMessage.length() > MAX_ERROR_MESSAGE) {
            errorMessage = errorMessage.substring(0, MAX_ERROR_MESSAGE);
        }
        return new ToolTraceRecord(
            root,
            session,
            toolCallId,
            toolName,
            durationMs(item),
            status != null && !"ok".equals(status),
            text(item, "error_type"),
            errorMessage,
            startedAt(item));
    }


    private static Long durationMs(JsonNode item) {
        JsonNode value = item.get("duration_ms");
        if (value == null || !value.isNumber() || value.asDouble() < 0) {
            return null;
        }
        return Math.round(value.asDouble());
    }


    /** 도구가 끝난 시각에서 걸린 시간을 뺀 시작 시각이다. 끝난 시각이 없으면 null 이다. */
    private static Instant startedAt(JsonNode item) {
        JsonNode ended = item.get("ended_at");
        if (ended == null || !ended.isNumber() || ended.asDouble() <= 0) {
            return null;
        }
        long endedMillis = Math.round(ended.asDouble() * 1000);
        Long duration = durationMs(item);
        return Instant.ofEpochMilli(duration == null ? endedMillis : endedMillis - duration);
    }


    private static String text(JsonNode item, String key) {
        JsonNode value = item.get(key);
        return value != null && value.isString() ? value.asString() : null;
    }


    private static String textOrEmpty(JsonNode item, String key) {
        JsonNode value = item.get(key);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.isString() ? value.asString() : null;
    }


    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
