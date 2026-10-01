package com.bifos.assistant.connector.application;

import com.bifos.assistant.mcp.application.McpCallContext;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.Sha256;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * profile 플러그인이 커넥터 도구 호출 전에 보낸 판정 요청 본문에서 서명을 확인한 값이다(ADR-049).
 *
 * <p>계약은 {@code docs/connectors.md} 의 「도구 호출 판정」 이 갖는다. 서명은 {@link McpCallContext} 와 같은
 * HMAC-SHA256 이고 key 도 같다. 서명할 글만 다르다. {@code v1-connector-policy}, {@code hermes_tool},
 * {@code root_session_id}, {@code session_id}, {@code tool_call_id}, {@code args_json} 의 UTF-8 바이트를 SHA-256
 * 한 소문자 16진수를 이 순서로 {@code \n} 하나로 이은 UTF-8 이다. 인자를 글로 받고 그 글을 서명하므로 보내는 쪽과
 * JSON 직렬화가 달라도 검증이 맞는다.
 *
 * <p>{@code tool} 은 서명하지 않는다. hook 이 대응 파일에서 찾은 값일 뿐이고, 받는 쪽이 등록 이름을 다시 계산해
 * 견준다. 모르는 키는 무시해 플러그인이 칸을 먼저 더해도 깨지지 않는다.
 *
 * <p>어떤 이유로 거절하든 밖에는 {@link ErrorCode#CONNECTOR_POLICY_REJECTED} 하나만 보인다. 이유는 서버 로그에만
 * 남기고 토큰 해시와 서명 값과 본문은 로그에 적지 않는다.
 *
 * @param rootSessionId 호출한 session 의 {@code parent_session_id} 사슬에서 처음 session
 * @param sessionId 그 도구를 부른 session
 * @param toolCallId 그 도구 호출의 id. 다시 보내도 같다
 * @param hermesTool hook 이 받은 등록 이름
 * @param tool hook 이 대응 파일에서 찾은 원래 도구 이름. 찾지 못했으면 null
 * @param argsJson 도구 인자를 hook 이 직렬화한 JSON 글
 */
@Slf4j
public record ConnectorPolicyRequest(
        String rootSessionId, String sessionId, String toolCallId, String hermesTool, String tool, String argsJson) {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String VERSION_LINE = "v1-connector-policy";
    private static final int MAX_ID_CHARS = 128;
    /** 받는 인자 글의 UTF-8 바이트 상한이다. 판정의 상한보다 크게 받아, 큰 인자도 까닭을 남기고 거절하게 한다. */
    private static final int MAX_ARGS_BYTES = 64 * 1024;

    private static final Pattern TOOL_NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,128}$");

    /**
     * 판정 요청 본문을 읽고 서명을 확인한다.
     *
     * @param body 요청 본문을 읽은 JSON
     * @param tokenHash 요청을 인증한 토큰의 SHA-256 소문자 16진수
     * @throws ApiException {@link ErrorCode#CONNECTOR_POLICY_REJECTED}. 모양이 틀리거나 서명이 맞지 않을 때
     */
    public static ConnectorPolicyRequest verify(JsonNode body, String tokenHash) {
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
        String argsJson = requireText(body, "args_json");
        if (argsJson.getBytes(StandardCharsets.UTF_8).length > MAX_ARGS_BYTES) {
            throw reject("args_json 이 받는 크기를 넘는다");
        }
        if (!isJsonObject(argsJson)) {
            throw reject("args_json 이 JSON 객체가 아니다");
        }
        String tool = toolOrNull(body);
        String signature = requireText(body, "sig");
        if (!McpCallContext.SIGNATURE.matcher(signature).matches()) {
            throw reject("서명이 소문자 16진수 64자가 아니다");
        }

        String signed =
                String.join("\n", VERSION_LINE, hermesTool, rootSessionId, sessionId, toolCallId, Sha256.hex(argsJson));
        byte[] expected = McpCallContext.hmac(tokenHash, signed);
        if (!MessageDigest.isEqual(expected, HexFormat.of().parseHex(signature))) {
            throw reject("서명이 맞지 않는다");
        }
        return new ConnectorPolicyRequest(rootSessionId, sessionId, toolCallId, hermesTool, tool, argsJson);
    }

    private static String requireId(JsonNode body, String key) {
        String value = requireText(body, key);
        if (value.length() > MAX_ID_CHARS) {
            throw reject(key + " 가 받는 길이를 넘는다");
        }
        return value;
    }

    private static String requireText(JsonNode body, String key) {
        JsonNode value = body.get(key);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw reject(key + " 가 없거나 비었거나 문자열이 아니다");
        }
        return value.asString();
    }

    /** 없거나 {@code null} 이면 null 이다. 문자열이면 도구 이름 형식이어야 한다. */
    private static String toolOrNull(JsonNode body) {
        JsonNode value = body.get("tool");
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString() || !TOOL_NAME.matcher(value.asString()).matches()) {
            throw reject("tool 이 도구 이름 형식의 문자열이나 null 이 아니다");
        }
        return value.asString();
    }

    private static boolean isJsonObject(String text) {
        try {
            return JSON.readTree(text).isObject();
        } catch (JacksonException ex) {
            return false;
        }
    }

    /** 이유는 로그에만 남긴다. 밖으로 나가는 문구는 이유를 가리지 않고 같다. */
    private static ApiException reject(String reason) {
        log.warn("커넥터 정책 요청 본문을 거절했다 reason={}", reason);
        return new ApiException(ErrorCode.CONNECTOR_POLICY_REJECTED, "connector policy request is rejected");
    }
}
