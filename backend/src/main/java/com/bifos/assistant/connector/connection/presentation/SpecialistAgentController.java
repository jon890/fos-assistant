package com.bifos.assistant.connector.connection.presentation;

import com.bifos.assistant.connector.connection.application.SpecialistAgentCreator;
import com.bifos.assistant.connector.connection.presentation.ConnectionDtos.SpecialistAgentRequest;
import com.bifos.assistant.connector.connection.presentation.ConnectionDtos.SpecialistAgentResponse;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 연결 하나만 맡는 전문 에이전트를 만든다(docs/features/agent-team/spec.md 의 REQ5, REQ6).
 *
 * <p>누구의 연결인지는 로그인에서만 정한다. 만든 에이전트의 주인은 요청자이고 늘 비공개다.
 */
@RestController
@RequestMapping("/api/v1/connections")
@RequiredArgsConstructor
public class SpecialistAgentController {
    private final SpecialistAgentCreator creator;
    private final CurrentUserProvider currentUser;


    @PostMapping("/{connectorId}/specialist")
    @ResponseStatus(HttpStatus.CREATED)
    public SpecialistAgentResponse create(@PathVariable String connectorId, @RequestBody SpecialistAgentRequest request) {
        return SpecialistAgentResponse.from(
            creator.create(currentUser.require(), connectorId, request.toIntroduction(), request.detach()));
    }
}
