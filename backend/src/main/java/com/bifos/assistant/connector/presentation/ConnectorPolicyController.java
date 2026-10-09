package com.bifos.assistant.connector.presentation;

import com.bifos.assistant.connector.application.ConnectorPolicyRequest;
import com.bifos.assistant.connector.application.ConnectorPolicyService;
import com.bifos.assistant.connector.presentation.ConnectionDtos.ConnectorPolicyResponse;
import com.bifos.assistant.mcp.application.McpPrincipal;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 연결용 profile 의 hook 이 커넥터 도구 호출 전에 판정을 묻는 경로다(ADR-049).
 *
 * <p>계약은 {@code backend/docs/flow.md} 의 「도구 호출 판정」 이 갖는다. 인증은 {@code /mcp} 와 같은 profile 토큰이고
 * 모델 도구가 아니다. 본문을 문자열로 받아 여기서 읽는다. JSON 이 아닌 본문도 다른 거절과 같은
 * {@link ErrorCode#CONNECTOR_POLICY_REJECTED} 로 끝나야 하기 때문이다. 막는 판정은 오류가 아니라 200 의
 * {@code block} 으로 답한다.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ConnectorPolicyController {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ConnectorPolicyService policies;

    @PostMapping("/internal/hermes/connector-policy")
    public ConnectorPolicyResponse decide(
            @AuthenticationPrincipal Object principal, @RequestBody(required = false) String body) {
        if (!(principal instanceof McpPrincipal mcp)) {
            throw reject("MCP 토큰으로 인증한 요청이 아니다");
        }
        ConnectorPolicyRequest request = ConnectorPolicyRequest.verify(read(body), mcp.tokenHash());
        return ConnectorPolicyResponse.from(policies.decide(
                mcp.profileName(),
                request.rootSessionId(),
                request.sessionId(),
                request.toolCallId(),
                request.hermesTool(),
                request.tool(),
                request.argsJson()));
    }

    private static JsonNode read(String body) {
        if (body == null || body.isBlank()) {
            throw reject("본문이 비었다");
        }
        try {
            return JSON.readTree(body);
        } catch (JacksonException ex) {
            throw reject("본문이 JSON 이 아니다");
        }
    }

    /** 이유는 로그에만 남긴다. 밖으로 나가는 문구는 이유를 가리지 않고 같다. */
    private static ApiException reject(String reason) {
        log.warn("커넥터 정책 요청을 거절했다 reason={}", reason);
        return new ApiException(ErrorCode.CONNECTOR_POLICY_REJECTED, "connector policy request is rejected");
    }
}
