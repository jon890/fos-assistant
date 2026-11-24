package com.bifos.assistant.mcp.presentation;

import com.bifos.assistant.mcp.application.dto.McpPrincipal;
import com.bifos.assistant.mcp.application.dto.RunTraceBatch;
import com.bifos.assistant.mcp.presentation.McpDtos.RunTraceResponse;
import com.bifos.assistant.orchestration.application.RunTraceIntake;
import com.bifos.assistant.orchestration.application.dto.ToolTraceRecord;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * profile 플러그인이 {@code post_tool_call} hook 에서 모은 도구 기록을 받는 경로다(docs/features/run-trace/design.md).
 *
 * <p>계약은 {@code hermes/plugins/fos-ctx/README.md} 의 「도구 기록을 보낸다」 가 갖는다. 인증은 {@code /mcp} 와 같은 profile 토큰이다.
 * 본문을 문자열로 받아 여기서 읽는다. 읽지 못한 본문의 조각이 예외 글로 로그에 남지 않게 하려는 것이다. 기록은 관측용이라 모양이 틀린
 * 기록은 버리고 202 로 답한다. plugin 은 답을 기다리지 않고 다시 보내지도 않는다(스펙 run-trace REQ7).
 */
@RestController
@Slf4j
@RequiredArgsConstructor
public class RunTraceController {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RunTraceIntake intake;


    @PostMapping("/internal/hermes/run-trace")
    public ResponseEntity<RunTraceResponse> receive(
        @AuthenticationPrincipal Object principal, @RequestBody(required = false) String body) {
        if (!(principal instanceof McpPrincipal mcp)) {
            log.warn("실행 기록 묶음을 거절했다 reason=MCP 토큰으로 인증한 요청이 아니다");
            throw new ApiException(ErrorCode.SESSION_BINDING_REJECTED, "session binding is rejected");
        }
        List<ToolTraceRecord> tools = RunTraceBatch.verifiedTools(read(body), mcp.tokenHash());
        int accepted = tools.isEmpty() ? 0 : intake.receive(mcp.profileName(), tools);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new RunTraceResponse(accepted));
    }


    private static JsonNode read(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(body);
        } catch (JacksonException ex) {
            log.warn("실행 기록 묶음을 거절했다 reason=본문이 JSON 이 아니다");
            return null;
        }
    }
}
