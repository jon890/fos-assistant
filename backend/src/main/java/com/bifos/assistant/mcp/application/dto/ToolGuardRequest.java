package com.bifos.assistant.mcp.application.dto;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.security.MessageDigest;
import java.util.HexFormat;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

/**
 * profile 플러그인이 안전 목록 밖의 도구 호출 전에 보낸 도구 판정 요청 본문에서 서명을 확인한 값이다(ADR-20261105 / event-watch).
 *
 * <p>계약은 {@code hermes/plugins/fos-ctx/README.md} 의 「도구 판정」 이 갖는다. 서명은 {@link McpCallContext} 와 같은 HMAC-SHA256 이고
 * key 도 같다. 서명할 글은 {@code v1-tool-guard}, {@code hermes_tool}, {@code root_session_id}, {@code session_id},
 * {@code tool_call_id} 를 이 순서로 {@code \n} 하나로 이은 UTF-8 이다. 도구 인자는 받지 않는다. 판정은 session 과 도구 이름으로만 한다.
 *
 * <p>어떤 이유로 거절하든 밖에는 {@link ErrorCode#TOOL_GUARD_REJECTED} 하나만 보인다. 이유는 서버 로그에만 남기고 토큰 해시와 서명
 * 값과 본문은 로그에 적지 않는다.
 *
 * @param rootSessionId 호출한 session 의 {@code parent_session_id} 사슬에서 처음 session
 * @param sessionId 그 도구를 부른 session
 * @param toolCallId 그 도구 호출의 id
 * @param hermesTool hook 이 받은 등록 이름
 */
@Slf4j
public record ToolGuardRequest(String rootSessionId, String sessionId, String toolCallId, String hermesTool) {


    private static final String VERSION_LINE = "v1-tool-guard";
    private static final int MAX_ID_CHARS = 128;

    /**
     * @param body 요청 본문을 읽은 JSON
     * @param tokenHash 요청을 인증한 토큰의 SHA-256 소문자 16진수
     * @throws ApiException {@link ErrorCode#TOOL_GUARD_REJECTED}. 모양이 틀리거나 서명이 맞지 않을 때
     */
    public static ToolGuardRequest verify(JsonNode body, String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) {
            throw reject("요청에 토큰 해시가 없다");
        }
        if (body == null || !body.isObject()) {
            throw reject("본문이 객체가 아니다");
        }
        if (!McpCallContext.isVersionOne(body.get("v"))) {
            throw reject("v 가 정수 1 이 아니다");
        }
        String rootSessionId = requireId(body, "root_session_id");
        String sessionId = requireId(body, "session_id");
        String toolCallId = requireId(body, "tool_call_id");
        String hermesTool = requireId(body, "hermes_tool");
        String signature = requireId(body, "sig");
        if (!McpCallContext.SIGNATURE.matcher(signature).matches()) {
            throw reject("서명이 소문자 16진수 64자가 아니다");
        }
        String signed = String.join("\n", VERSION_LINE, hermesTool, rootSessionId, sessionId, toolCallId);
        byte[] expected = McpCallContext.hmac(tokenHash, signed);
        if (!MessageDigest.isEqual(expected, HexFormat.of().parseHex(signature))) {
            throw reject("서명이 맞지 않는다");
        }
        return new ToolGuardRequest(rootSessionId, sessionId, toolCallId, hermesTool);
    }


    private static String requireId(JsonNode body, String key) {
        JsonNode value = body.get(key);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw reject(key + " 가 없거나 비었거나 문자열이 아니다");
        }
        if (value.asString().length() > MAX_ID_CHARS) {
            throw reject(key + " 가 받는 길이를 넘는다");
        }
        return value.asString();
    }


    /** 이유는 로그에만 남긴다. 밖으로 나가는 문구는 이유를 가리지 않고 같다. */
    private static ApiException reject(String reason) {
        log.warn("도구 판정 요청 본문을 거절했다 reason={}", reason);
        return new ApiException(ErrorCode.TOOL_GUARD_REJECTED, "tool guard request is rejected");
    }
}
