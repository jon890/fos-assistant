package com.bifos.assistant.mcp.application.dto;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.security.MessageDigest;
import java.util.HexFormat;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

/**
 * profile 플러그인이 {@code subagent_stop} hook 에서 보낸 하위 에이전트 끝 소식에서 서명을 확인한 값이다.
 *
 * <p>계약은 {@code hermes/plugins/fos-ctx/README.md} 의 「하위 에이전트 끝 소식 계약」 과 ADR-20261117 / native-delegation-wake 가
 * 갖는다. 서명은 등록({@link SubagentRegistration})과 같은 HMAC-SHA256 과 key 이고 서명할 글의 첫 줄만
 * {@code v1-subagent-stop} 이다. 그래서 등록 서명을 끝 소식에 쓸 수 없다. 상태와 결과 글은 서명하지 않는다. 토큰이 profile 을
 * 증명하고 서명은 session 세 값을 묶는다.
 *
 * <p>어떤 이유로 거절하든 밖에는 {@link ErrorCode#SESSION_BINDING_REJECTED} 하나만 보인다. 토큰 해시와 서명과 본문은 로그에
 * 적지 않는다.
 *
 * @param parentRootSessionId 부모 session 사슬의 처음 session
 * @param parentSessionId 하위 에이전트를 만든 session
 * @param childSessionId 끝난 하위 에이전트 session
 * @param childStatus hook 이 받은 {@code child_status}. 없으면 {@code null}
 * @param childSummary hook 이 받은 결과 글. 없으면 {@code null}
 */
@Slf4j
public record SubagentStop(
    String parentRootSessionId,
    String parentSessionId,
    String childSessionId,
    String childStatus,
    String childSummary) {


    private static final String VERSION_LINE = "v1-subagent-stop";

    /**
     * 끝 소식 본문을 읽고 서명을 확인한다.
     *
     * @param body 요청 본문을 읽은 JSON
     * @param tokenHash 요청을 인증한 토큰의 SHA-256 소문자 16진수
     * @throws ApiException {@link ErrorCode#SESSION_BINDING_REJECTED}. 모양이 틀리거나 서명이 맞지 않을 때
     */
    public static SubagentStop verify(JsonNode body, String tokenHash) {
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
        String childStatus = textOrNull(body, "child_status");
        String childSummary = textOrNull(body, "child_summary");

        String signed = String.join("\n", VERSION_LINE, parentRootSessionId, parentSessionId, childSessionId);
        byte[] expected = McpCallContext.hmac(tokenHash, signed);
        if (!MessageDigest.isEqual(expected, HexFormat.of().parseHex(signature))) {
            throw reject("서명이 맞지 않는다");
        }
        return new SubagentStop(parentRootSessionId, parentSessionId, childSessionId, childStatus, childSummary);
    }


    /** 결과 글이 로그에 남지 않게 글자 수만 낸다. */
    @Override
    public String toString() {
        return "SubagentStop[childStatus=" + childStatus
            + ", summaryChars=" + (childSummary == null ? 0 : childSummary.length()) + "]";
    }


    private static String requireText(JsonNode body, String key) {
        JsonNode value = body.get(key);
        if (value == null || !value.isTextual() || value.asString().isBlank()) {
            throw reject(key + " 가 없거나 비었거나 문자열이 아니다");
        }
        return value.asString();
    }


    /** 없거나 {@code null} 이면 {@code null}, 문자열이면 그 값이다. 다른 타입이면 거절한다. */
    private static String textOrNull(JsonNode body, String key) {
        JsonNode value = body.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw reject(key + " 가 문자열이나 null 이 아니다");
        }
        return value.asString();
    }


    /** 이유는 로그에만 남긴다. 밖으로 나가는 문구는 이유를 가리지 않고 같다. */
    private static ApiException reject(String reason) {
        log.warn("하위 에이전트 끝 소식 본문을 거절했다 reason={}", reason);
        return new ApiException(ErrorCode.SESSION_BINDING_REJECTED, "session binding is rejected");
    }
}
