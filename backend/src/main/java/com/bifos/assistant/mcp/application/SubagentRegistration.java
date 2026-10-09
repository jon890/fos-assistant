package com.bifos.assistant.mcp.application;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.security.MessageDigest;
import java.util.HexFormat;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

/**
 * profile 플러그인이 {@code subagent_start} hook 에서 보낸 하위 에이전트 session 등록 본문에서 서명을 확인한 값이다.
 *
 * <p>계약은 ADR-037 과 {@code hermes/plugins/fos-ctx/README.md} 의 「하위 에이전트 session 등록 계약」 이 갖는다. 서명은
 * {@link McpCallContext} 와 같은 HMAC-SHA256 이고 key 도 같다. 서명할 글만 다르다. {@code v1-subagent},
 * {@code parent_root_session_id}, {@code parent_session_id}, {@code child_session_id} 를 이 순서로 {@code \n}
 * 하나로 이은 UTF-8 이다. 첫 줄이 {@code _fos_ctx} 의 도구 이름 자리와 달라 두 서명은 서로 쓰이지 않는다.
 *
 * <p>{@code child_subagent_id}, {@code parent_subagent_id} 는 서명하지 않고 저장하지도 않는다. 문자열이나
 * {@code null} 인지만 본다. 모르는 키는 무시해 플러그인이 칸을 먼저 더해도 깨지지 않는다.
 *
 * <p>어떤 이유로 거절하든 밖에는 {@link ErrorCode#SESSION_BINDING_REJECTED} 하나만 보인다. 이유는 서버 로그에만
 * 남기고 토큰 해시와 서명 값과 본문은 로그에 적지 않는다.
 *
 * @param parentRootSessionId 부모 session 의 {@code parent_session_id} 사슬에서 처음 session
 * @param parentSessionId 하위 에이전트를 만든 session
 * @param childSessionId 등록할 하위 에이전트 session
 */
@Slf4j
public record SubagentRegistration(String parentRootSessionId, String parentSessionId, String childSessionId) {
    private static final String VERSION_LINE = "v1-subagent";

    /**
     * 등록 본문을 읽고 서명을 확인한다.
     *
     * @param body 요청 본문을 읽은 JSON
     * @param tokenHash 요청을 인증한 토큰의 SHA-256 소문자 16진수
     * @throws ApiException {@link ErrorCode#SESSION_BINDING_REJECTED}. 모양이 틀리거나 서명이 맞지 않을 때
     */
    public static SubagentRegistration verify(JsonNode body, String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) {
            throw reject("요청에 토큰 해시가 없다");
        }
        if (body == null || !body.isObject()) {
            throw reject("본문이 객체가 아니다");
        }
        if (!McpCallContext.isVersionOne(body.get("v"))) {
            throw reject("v 가 정수 1 이 아니다");
        }
        String parentSessionId = requireText(body, "parent_session_id");
        String parentRootSessionId = requireText(body, "parent_root_session_id");
        String childSessionId = requireText(body, "child_session_id");
        String signature = requireText(body, "sig");
        if (!McpCallContext.SIGNATURE.matcher(signature).matches()) {
            throw reject("서명이 소문자 16진수 64자가 아니다");
        }
        requireTextOrNull(body, "child_subagent_id");
        requireTextOrNull(body, "parent_subagent_id");

        String signed = String.join("\n", VERSION_LINE, parentRootSessionId, parentSessionId, childSessionId);
        byte[] expected = McpCallContext.hmac(tokenHash, signed);
        if (!MessageDigest.isEqual(expected, HexFormat.of().parseHex(signature))) {
            throw reject("서명이 맞지 않는다");
        }
        return new SubagentRegistration(parentRootSessionId, parentSessionId, childSessionId);
    }

    private static String requireText(JsonNode body, String key) {
        JsonNode value = body.get(key);
        if (value == null || !value.isTextual() || value.asString().isBlank()) {
            throw reject(key + " 가 없거나 비었거나 문자열이 아니다");
        }
        return value.asString();
    }

    /** 없거나 {@code null} 이거나 문자열이면 받는다. */
    private static void requireTextOrNull(JsonNode body, String key) {
        JsonNode value = body.get(key);
        if (value != null && !value.isNull() && !value.isTextual()) {
            throw reject(key + " 가 문자열이나 null 이 아니다");
        }
    }

    /** 이유는 로그에만 남긴다. 밖으로 나가는 문구는 이유를 가리지 않고 같다. */
    private static ApiException reject(String reason) {
        log.warn("하위 에이전트 session 등록 본문을 거절했다 reason={}", reason);
        return new ApiException(ErrorCode.SESSION_BINDING_REJECTED, "session binding is rejected");
    }
}
