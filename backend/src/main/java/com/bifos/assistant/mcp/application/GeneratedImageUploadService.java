package com.bifos.assistant.mcp.application;

import com.bifos.assistant.chat.artifact.application.GeneratedImageService;
import com.bifos.assistant.chat.artifact.application.dto.GeneratedImageSaved;
import com.bifos.assistant.mcp.application.dto.GeneratedImageRequest;
import com.bifos.assistant.mcp.application.dto.McpPrincipal;
import com.bifos.assistant.orchestration.application.SessionOwnerResolver;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * profile 플러그인이 올린 그림의 주인과 대화를 origin 실행에서 정해 저장을 맡긴다(ADR-20261119 / generated-image).
 *
 * <p><b>요청 본문이 사용자나 대화를 정하지 못한다.</b> 서명을 확인한 session 에서 origin 실행을 찾고(ADR-037) 그 실행의 사용자와 대화를
 * 쓴다. 다른 에이전트에게 맡긴 실행과 Hermes 내장 위임의 자식도 같은 길로 부모 대화에 닿는다.
 *
 * <p>origin 실행이 끝났어도 받는다. Hermes 내장 위임의 자식은 부모 turn 이 끝난 뒤에도 돌 수 있고, 그 그림은 그 대화의 다음 답에 묶인다.
 * 중지한 실행과 중지한 트리의 그림은 {@link SessionOwnerResolver} 가 거절한다. 사용자가 멈춘 turn 의 권한을 쓰지 못하게 하는 기존 규칙이다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GeneratedImageUploadService {

    private final SessionOwnerResolver owners;
    private final GeneratedImageService images;


    /**
     * @throws ApiException {@link ErrorCode#MCP_CALL_CONTEXT_INVALID} 는 서명이나 origin 실행이 맞지 않거나 중지한 실행일 때,
     * {@link ErrorCode#VALIDATION_FAILED} 는 그림 형식이나 크기가 맞지 않을 때다
     */
    public GeneratedImageSaved upload(McpPrincipal principal, JsonNode meta, byte[] image) {
        GeneratedImageRequest request = GeneratedImageRequest.verify(meta, image, principal.tokenHash());
        AgentExecution origin = owners.resolve(principal.profileName(), request.rootSessionId(), request.sessionId());
        if (origin.conversationId() == null || origin.userId() == null) {
            log.warn("만든 그림 올리기를 거절했다 reason={} executionId={}", "대화나 사용자가 없는 실행이다", origin.id());
            throw new ApiException(ErrorCode.MCP_CALL_CONTEXT_INVALID, "call context is invalid");
        }
        return images.save(
            origin.conversationId(), origin.userId(), request.callKey(), request.title(), request.prompt(), request.sourceLinks(), image);
    }
}
