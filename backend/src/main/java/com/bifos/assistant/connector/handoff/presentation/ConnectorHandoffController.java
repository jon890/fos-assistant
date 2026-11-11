package com.bifos.assistant.connector.handoff.presentation;

import com.bifos.assistant.chat.conversation.application.ConversationAccess;
import com.bifos.assistant.connector.handoff.application.ConnectorHandoffs;
import com.bifos.assistant.connector.handoff.application.dto.ConnectorHandoffRequest;
import com.bifos.assistant.connector.handoff.application.dto.HandoffView;
import com.bifos.assistant.mcp.application.dto.McpPrincipal;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 화면 넘기기의 두 경로다. profile 의 hook 이 알리는 내부 경로와, 화면이 대화의 카드를 읽는 경로다.
 *
 * <p>계약은 {@code docs/features/naver-shopping/design.md} 의 「PR2 화면 넘기기」 가 갖는다. 내부 경로의 인증은
 * {@code /internal/hermes/connector-policy} 와 같은 profile 토큰이다.
 */
@RestController
@RequiredArgsConstructor
public class ConnectorHandoffController {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ConnectorHandoffs handoffs;
    private final ConversationAccess conversations;
    private final CurrentUserProvider currentUser;


    @PostMapping("/internal/hermes/connector-handoff")
    public Map<String, Object> record(
        @AuthenticationPrincipal Object principal, @RequestBody(required = false) String body) {
        if (!(principal instanceof McpPrincipal mcp)) {
            throw ConnectorHandoffRequest.reject("MCP 토큰으로 인증한 요청이 아니다");
        }
        handoffs.record(mcp.profileName(), ConnectorHandoffRequest.verify(read(body), mcp.tokenHash()));
        return Map.of();
    }


    /** 주소의 번호는 대화의 공개 식별자다. 주인의 대화가 아니면 다른 대화 경로와 같은 응답이다. */
    @GetMapping("/api/v1/chat/conversations/{conversationId}/handoffs")
    public List<HandoffView> open(@PathVariable UUID conversationId) {
        CurrentUser user = currentUser.require();
        return handoffs.open(user, conversations.requireOwnId(user, conversationId));
    }


    private static JsonNode read(String body) {
        if (body == null || body.isBlank()) {
            throw ConnectorHandoffRequest.reject("본문이 비었다");
        }
        try {
            return JSON.readTree(body);
        } catch (JacksonException ex) {
            throw ConnectorHandoffRequest.reject("본문이 JSON 이 아니다");
        }
    }
}
