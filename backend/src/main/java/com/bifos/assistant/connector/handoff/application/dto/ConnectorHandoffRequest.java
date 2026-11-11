package com.bifos.assistant.connector.handoff.application.dto;

import com.bifos.assistant.connector.action.domain.type.HandoffNeed;
import com.bifos.assistant.mcp.application.dto.McpCallContext;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

/**
 * fos-ctx 의 {@code transform_tool_result} hook 이 보낸 화면 넘기기 알림에서 서명을 확인한 값이다.
 *
 * <p>계약은 {@code docs/features/naver-shopping/design.md} 의 「fos-ctx 가 알리는 것」 이 갖는다. 서명은
 * {@link McpCallContext} 와 같은 HMAC-SHA256 이고 key 도 같다. 서명할 글은 {@code v1-connector-handoff},
 * {@code hermes_tool}, {@code root_session_id}, {@code session_id}, {@code tool_call_id}, {@code code}, {@code need},
 * {@code tab} 을 이 순서로 {@code \n} 하나로 이은 UTF-8 이다.
 *
 * <p>어떤 이유로 거절하든 밖에는 {@link ErrorCode#CONNECTOR_POLICY_REJECTED} 하나만 보인다. 이유는 서버 로그에만 남긴다.
 */
@Slf4j
public record ConnectorHandoffRequest(
    String rootSessionId, String sessionId, String toolCallId, String hermesTool, String code, HandoffNeed need,
    String tab) {


    private static final String VERSION_LINE = "v1-connector-handoff";
    private static final Pattern ID = Pattern.compile("^.{1,128}$", Pattern.DOTALL);
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9_]{1,64}$");
    /** 화면 입력 {@code tab} 의 {@code id} 와 같은 모양이다. */
    private static final Pattern TAB = Pattern.compile("^[A-Za-z0-9]{1,128}$");

    /**
     * 본문을 읽고 서명을 확인한다.
     *
     * @param tokenHash 요청을 인증한 토큰의 SHA-256 소문자 16진수
     * @throws ApiException {@link ErrorCode#CONNECTOR_POLICY_REJECTED}. 모양이 틀리거나 서명이 맞지 않을 때
     */
    public static ConnectorHandoffRequest verify(JsonNode body, String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank() || body == null || !body.isObject()) {
            throw reject("토큰 해시나 본문 객체가 없다");
        }
        if (!McpCallContext.isVersionOne(body.get("v"))) {
            throw reject("v 가 정수 1 이 아니다");
        }
        String root = require(body, "root_session_id", ID);
        String session = require(body, "session_id", ID);
        String call = require(body, "tool_call_id", ID);
        String hermesTool = require(body, "hermes_tool", ID);
        String code = require(body, "code", CODE);
        String needWord = require(body, "need", ID);
        String tab = require(body, "tab", TAB);
        HandoffNeed need = HandoffNeed.fromWire(needWord).orElseThrow(() -> reject("need 가 어휘 밖이다"));
        String signature = require(body, "sig", McpCallContext.SIGNATURE);
        String signed = String.join("\n", VERSION_LINE, hermesTool, root, session, call, code, needWord, tab);
        if (!MessageDigest.isEqual(McpCallContext.hmac(tokenHash, signed), HexFormat.of().parseHex(signature))) {
            throw reject("서명이 맞지 않는다");
        }
        return new ConnectorHandoffRequest(root, session, call, hermesTool, code, need, tab);
    }


    private static String require(JsonNode body, String key, Pattern shape) {
        JsonNode value = body.get(key);
        if (value == null || !value.isString() || value.asString().isBlank()
            || !shape.matcher(value.asString()).matches()) {
            throw reject(key + " 가 없거나 모양이 틀렸다");
        }
        return value.asString();
    }


    /** 이유는 로그에만 남긴다. 밖으로 나가는 문구는 이유를 가리지 않고 같다. */
    public static ApiException reject(String reason) {
        log.warn("화면 넘기기 알림을 거절했다 reason={}", reason);
        return new ApiException(ErrorCode.CONNECTOR_POLICY_REJECTED, "connector handoff request is rejected");
    }
}
