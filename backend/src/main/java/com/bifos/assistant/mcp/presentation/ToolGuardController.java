package com.bifos.assistant.mcp.presentation;

import com.bifos.assistant.mcp.application.dto.McpPrincipal;
import com.bifos.assistant.mcp.application.dto.ToolGuardRequest;
import com.bifos.assistant.mcp.presentation.McpDtos.ToolGuardResponse;
import com.bifos.assistant.orchestration.application.EventToolGuard;
import com.bifos.assistant.orchestration.application.dto.ToolGuardAnswer;
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
 * profile 의 hook 이 안전 목록 밖의 도구 호출 전에 사건 실행 트리인지 묻는 경로다(ADR-20261105 / event-watch).
 *
 * <p>계약은 {@code hermes/plugins/fos-ctx/README.md} 의 「도구 판정」 이 갖는다. 인증은 {@code /mcp} 와 같은 profile 토큰이다. 막는
 * 판정은 오류가 아니라 200 의 {@code block} 이다. <b>이 경로는 404 를 내지 않는다.</b> hook 이 404 를 이 경로가 없는 옛 Control Plane
 * 으로 읽고 통과시키기 때문이다. 그래서 판정 중의 어떤 예외든 200 의 {@code block} 으로 답한다.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ToolGuardController {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final String FAILED_MESSAGE = "이 도구를 지금 쓸 수 있는지 확인하지 못해 막았다. 잠시 뒤 다시 시도하라고 사용자에게 알린다.";

    private final EventToolGuard guard;


    @PostMapping("/internal/hermes/tool-guard")
    public ToolGuardResponse decide(@AuthenticationPrincipal Object principal, @RequestBody(required = false) String body) {
        if (!(principal instanceof McpPrincipal mcp)) {
            log.warn("도구 판정 요청을 거절했다 reason={}", "MCP 토큰으로 인증한 요청이 아니다");
            throw new ApiException(ErrorCode.TOOL_GUARD_REJECTED, "tool guard request is rejected");
        }
        ToolGuardRequest request = ToolGuardRequest.verify(read(body), mcp.tokenHash());
        try {
            ToolGuardAnswer answer = guard.decide(mcp.profileName(), request.rootSessionId(), request.sessionId());
            return answer.allowed() ? ToolGuardResponse.allow() : ToolGuardResponse.block(answer.message());
        } catch (RuntimeException ex) {
            // 판정이 던지는 *_NOT_FOUND 가 404 로 나가면 hook 이 통과시킨다. 막는 답으로 바꾼다.
            log.warn("도구 판정이 실패해 막았다 profile={}", mcp.profileName(), ex);
            return ToolGuardResponse.block(FAILED_MESSAGE);
        }
    }


    private static JsonNode read(String body) {
        if (body == null || body.isBlank()) {
            log.warn("도구 판정 요청을 거절했다 reason={}", "본문이 비었다");
            throw new ApiException(ErrorCode.TOOL_GUARD_REJECTED, "tool guard request is rejected");
        }
        try {
            return JSON.readTree(body);
        } catch (JacksonException ex) {
            log.warn("도구 판정 요청을 거절했다 reason={}", "본문이 JSON 이 아니다");
            throw new ApiException(ErrorCode.TOOL_GUARD_REJECTED, "tool guard request is rejected");
        }
    }
}
