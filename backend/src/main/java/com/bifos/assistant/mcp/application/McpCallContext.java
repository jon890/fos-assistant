package com.bifos.assistant.mcp.application;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * profile 플러그인이 MCP 도구 인자에 덮어쓴 {@code _fos_ctx} 에서 서명을 확인한 값이다.
 *
 * <p>계약과 근거는 ADR-031 과 {@code docs/hermes/delegation.md} 의 「{@code _fos_ctx} 계약」 이 갖는다.
 * 서명은 HMAC-SHA256 이고, key 는 그 profile 의 MCP 토큰을 SHA-256 한 소문자 16진수 64자 문자열의
 * UTF-8 바이트다. 16진수를 풀어 낸 32바이트가 아니다. 서명할 글은 {@code v1}, 도구 이름,
 * {@code root_session_id}, {@code session_id}, {@code tool_call_id} 를 이 순서로 {@code \n} 하나로
 * 이은 UTF-8 이다. 모델이 준 다른 도구 인자는 서명에 들어가지 않고 여기서 보지도 않는다.
 *
 * <p>어떤 이유로 거절하든 밖에는 같은 {@link ErrorCode#MCP_CALL_CONTEXT_INVALID} 하나만 보인다. 이유는
 * 서버 로그에만 남기고, 토큰 해시와 {@code sig} 값은 로그에 적지 않는다.
 *
 * @param rootSessionId 호출한 session 의 {@code parent_session_id} 사슬에서 처음 session
 * @param sessionId 그 호출의 session
 * @param toolCallId 그 도구 호출의 id. Hermes 가 다시 보내도 같다
 */
public record McpCallContext(String rootSessionId, String sessionId, String toolCallId) {

    /** 도구 인자 안에서 이 값이 놓이는 키다. */
    public static final String FIELD = "_fos_ctx";

    private static final Logger log = LoggerFactory.getLogger(McpCallContext.class);
    private static final String VERSION_LINE = "v1";
    private static final String HMAC = "HmacSHA256";
    /** {@code sig} 가 받는 모양이다. 하위 에이전트 등록 서명도 같은 모양이다. */
    static final Pattern SIGNATURE = Pattern.compile("^[0-9a-f]{64}$");

    /**
     * {@code _fos_ctx} 를 읽고 서명을 확인한다.
     *
     * @param toolName 서버 쪽 도구 이름. 서명할 글에 들어가므로 다른 도구의 서명은 통과하지 못한다
     * @param fosCtx 도구 인자의 {@code _fos_ctx} 값. 없으면 null
     * @param tokenHash 요청을 인증한 토큰의 SHA-256 소문자 16진수
     * @throws ApiException {@link ErrorCode#MCP_CALL_CONTEXT_INVALID}. 모양이 틀리거나 서명이 맞지 않을 때
     */
    public static McpCallContext verify(String toolName, JsonNode fosCtx, String tokenHash) {
        if (toolName == null || toolName.isBlank()) throw reject(toolName, "도구 이름이 없다");
        if (tokenHash == null || tokenHash.isBlank()) throw reject(toolName, "요청에 토큰 해시가 없다");
        if (fosCtx == null || !fosCtx.isObject()) throw reject(toolName, "_fos_ctx 가 없거나 객체가 아니다");
        if (!isVersionOne(fosCtx.get("v"))) throw reject(toolName, "v 가 정수 1 이 아니다");
        String rootSessionId = requireText(toolName, fosCtx, "root_session_id");
        String sessionId = requireText(toolName, fosCtx, "session_id");
        String toolCallId = requireText(toolName, fosCtx, "tool_call_id");
        String signature = requireText(toolName, fosCtx, "sig");
        if (!SIGNATURE.matcher(signature).matches()) throw reject(toolName, "sig 가 소문자 16진수 64자가 아니다");

        String signed = String.join("\n", VERSION_LINE, toolName, rootSessionId, sessionId, toolCallId);
        byte[] expected = hmac(tokenHash, signed);
        if (!MessageDigest.isEqual(expected, HexFormat.of().parseHex(signature))) {
            throw reject(toolName, "sig 가 맞지 않는다");
        }
        return new McpCallContext(rootSessionId, sessionId, toolCallId);
    }

    /**
     * JSON 정수 1 만 받는다.
     *
     * <p>{@code 1.0} 과 {@code 1e0} 은 소수 노드로 읽혀 거절한다. 플러그인은 정수 1 을 보내고, 같은 값을 여러
     * 표기로 받아 주면 판을 올릴 때 구분할 자리가 흐려진다. 문자열 {@code "1"} 도 거절한다.
     * 하위 에이전트 등록의 {@code v} 도 같은 규칙으로 본다.
     */
    static boolean isVersionOne(JsonNode version) {
        return version != null && version.isIntegralNumber() && version.canConvertToLong() && version.longValue() == 1L;
    }

    private static String requireText(String toolName, JsonNode fosCtx, String key) {
        JsonNode value = fosCtx.get(key);
        if (value == null || !value.isTextual() || value.asString().isBlank()) {
            throw reject(toolName, key + " 가 없거나 비었거나 문자열이 아니다");
        }
        return value.asString();
    }

    /** key 는 토큰 해시 문자열의 UTF-8 바이트다. 하위 에이전트 등록 서명도 같은 key 와 계산을 쓴다. */
    static byte[] hmac(String tokenHash, String text) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(tokenHash.getBytes(StandardCharsets.UTF_8), HMAC));
            return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 is unavailable", ex);
        }
    }

    /** 이유는 로그에만 남긴다. 밖으로 나가는 문구는 이유를 가리지 않고 같다. */
    private static ApiException reject(String toolName, String reason) {
        log.warn("MCP 호출 맥락을 거절했다 tool={} reason={}", toolName, reason);
        return new ApiException(ErrorCode.MCP_CALL_CONTEXT_INVALID, "call context is invalid");
    }
}
