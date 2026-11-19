package com.bifos.assistant.mcp.application.dto;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.Sha256;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

/**
 * profile 플러그인이 비서가 만든 그림을 올리며 보낸 `meta` 조각에서 서명을 확인한 값이다(ADR-20261119 / generated-image).
 *
 * <p>계약은 {@code hermes/plugins/fos-ctx/README.md} 의 「만든 그림을 올린다」 가 갖는다. 서명은 {@link McpCallContext} 와 같은 HMAC-SHA256 이고
 * key 도 같다. 서명할 글은 {@code v1-generated-image}, {@code root_session_id}, {@code session_id}, {@code tool_call_id}, 그림 바이트의
 * SHA-256, 제목의 SHA-256, 지시문의 SHA-256 을 이 순서로 {@code \n} 하나로 이은 UTF-8 이다. 해시는 소문자 16진수다.
 *
 * <p>어떤 이유로 거절하든 밖에는 {@link ErrorCode#MCP_CALL_CONTEXT_INVALID} 하나만 보인다. 이유는 서버 로그에만 남기고 토큰 해시와 서명
 * 값과 본문은 로그에 적지 않는다.
 *
 * @param title 모델이 붙인 제목. 없으면 빈 글이다
 * @param prompt 이미지 도구에 넘긴 지시문
 * @param imageSha256 그림 바이트의 SHA-256 소문자 16진수
 */
@Slf4j
public record GeneratedImageRequest(
    String rootSessionId, String sessionId, String toolCallId, String title, String prompt, String imageSha256) {


    private static final String VERSION_LINE = "v1-generated-image";
    private static final int MAX_ID_CHARS = 128;
    private static final int MAX_TITLE_CHARS = 500;
    private static final int MAX_PROMPT_CHARS = 32_000;

    /**
     * @param meta `meta` 조각을 읽은 JSON
     * @param image `image` 조각의 바이트
     * @param tokenHash 요청을 인증한 토큰의 SHA-256 소문자 16진수
     * @throws ApiException {@link ErrorCode#MCP_CALL_CONTEXT_INVALID}. 모양이 틀리거나 서명이 맞지 않을 때
     */
    public static GeneratedImageRequest verify(JsonNode meta, byte[] image, String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) {
            throw reject("요청에 토큰 해시가 없다");
        }
        if (meta == null || !meta.isObject() || image == null) {
            throw reject("meta 가 객체가 아니거나 그림이 없다");
        }
        if (!McpCallContext.isVersionOne(meta.get("v"))) {
            throw reject("v 가 정수 1 이 아니다");
        }
        String rootSessionId = requireId(meta, "root_session_id");
        String sessionId = requireId(meta, "session_id");
        String toolCallId = requireId(meta, "tool_call_id");
        String title = text(meta, "title", MAX_TITLE_CHARS);
        String prompt = text(meta, "prompt", MAX_PROMPT_CHARS);
        String signature = requireId(meta, "sig");
        if (!McpCallContext.SIGNATURE.matcher(signature).matches()) {
            throw reject("서명이 소문자 16진수 64자가 아니다");
        }
        String imageSha256 = sha256(image);
        String signed = String.join(
            "\n",
            VERSION_LINE,
            rootSessionId,
            sessionId,
            toolCallId,
            imageSha256,
            sha256(title.getBytes(StandardCharsets.UTF_8)),
            sha256(prompt.getBytes(StandardCharsets.UTF_8)));
        if (!MessageDigest.isEqual(McpCallContext.hmac(tokenHash, signed), HexFormat.of().parseHex(signature))) {
            throw reject("서명이 맞지 않는다");
        }
        return new GeneratedImageRequest(rootSessionId, sessionId, toolCallId, title, prompt, imageSha256);
    }


    /** 같은 올리기가 다시 와도 한 줄이 되게 하는 값이다. 같은 호출이라도 그림이 다르면 값이 다르다. */
    public String callKey() {
        return sha256(String.join("\n", sessionId, toolCallId, imageSha256).getBytes(StandardCharsets.UTF_8));
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


    private static String text(JsonNode body, String key, int maxChars) {
        JsonNode value = body.get(key);
        if (value == null || value.isNull()) {
            return "";
        }
        if (!value.isString() || value.asString().length() > maxChars) {
            throw reject(key + " 가 문자열이 아니거나 받는 길이를 넘는다");
        }
        return value.asString();
    }


    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(Sha256.newDigest().digest(bytes));
    }


    /** 이유는 로그에만 남긴다. 밖으로 나가는 문구는 이유를 가리지 않고 같다. */
    private static ApiException reject(String reason) {
        log.warn("만든 그림 올리기 요청을 거절했다 reason={}", reason);
        return new ApiException(ErrorCode.MCP_CALL_CONTEXT_INVALID, "call context is invalid");
    }
}
