package com.bifos.assistant.mcp.presentation;

import com.bifos.assistant.mcp.application.McpPrincipal;
import com.bifos.assistant.mcp.application.SubagentRegistration;
import com.bifos.assistant.mcp.presentation.McpDtos.SubagentRegistrationResponse;
import com.bifos.assistant.orchestration.application.SubagentRegistrationResult;
import com.bifos.assistant.orchestration.application.SubagentSessionRegistrar;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * profile 플러그인이 {@code subagent_start} hook 에서 하위 에이전트 session 을 등록하는 경로다(ADR-037).
 *
 * <p>계약은 {@code docs/hermes/delegation.md} 의 「하위 에이전트 session 등록 계약」 이 갖는다. 인증은
 * {@code /mcp} 와 같은 profile 토큰이고 모델 도구가 아니다. 본문을 문자열로 받아 여기서 읽는다. JSON 이 아닌
 * 본문도 다른 거절과 같은 {@link ErrorCode#SESSION_BINDING_REJECTED} 로 끝나야 하기 때문이다.
 */
@RestController
@RequiredArgsConstructor
public class SubagentSessionController {

    private static final Logger log = LoggerFactory.getLogger(SubagentSessionController.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SubagentSessionRegistrar registrar;

    @PostMapping("/internal/hermes/session-bindings/subagent")
    public ResponseEntity<SubagentRegistrationResponse> register(
            @AuthenticationPrincipal Object principal, @RequestBody(required = false) String body) {
        if (!(principal instanceof McpPrincipal mcp)) throw reject("MCP 토큰으로 인증한 요청이 아니다");
        SubagentRegistration registration = SubagentRegistration.verify(read(body), mcp.tokenHash());
        SubagentRegistrationResult result = registrar.register(mcp.profileName(),
                registration.parentRootSessionId(), registration.parentSessionId(), registration.childSessionId());
        return switch (result) {
            case CREATED -> ResponseEntity.status(HttpStatus.CREATED).body(new SubagentRegistrationResponse("created"));
            case EXISTS -> ResponseEntity.ok(new SubagentRegistrationResponse("exists"));
        };
    }

    private static JsonNode read(String body) {
        if (body == null || body.isBlank()) throw reject("본문이 비었다");
        try {
            return JSON.readTree(body);
        } catch (JacksonException ex) {
            throw reject("본문이 JSON 이 아니다");
        }
    }

    /** 이유는 로그에만 남긴다. 밖으로 나가는 문구는 이유를 가리지 않고 같다. */
    private static ApiException reject(String reason) {
        log.warn("하위 에이전트 session 등록을 거절했다 reason={}", reason);
        return new ApiException(ErrorCode.SESSION_BINDING_REJECTED, "session binding is rejected");
    }
}
