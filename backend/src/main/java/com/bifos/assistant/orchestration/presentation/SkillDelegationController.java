package com.bifos.assistant.orchestration.presentation;

import com.bifos.assistant.chat.conversation.application.ConversationAccess;
import com.bifos.assistant.orchestration.application.SkillDelegations;
import com.bifos.assistant.orchestration.presentation.SkillDelegationDtos.SkillDelegationRequest;
import com.bifos.assistant.orchestration.presentation.SkillDelegationDtos.SkillDelegationResponse;
import com.bifos.assistant.orchestration.presentation.SkillDelegationDtos.SkillDelegationTargetResponse;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 비서 대화의 {@code /} 목록에서 전문 에이전트의 스킬을 맡긴다(docs/features/assistant-hub/spec.md 의 REQ10). 대화 turn 을 여는 보내기
 * ({@code /api/v1/chat/messages})와 달리 실행을 띄우고 바로 돌려준다. 결과는 대화의 깨우기가 결과 도착 줄과 비서 답으로 전한다.
 *
 * <p>누구의 대화와 에이전트인지는 로그인에서만 정한다.
 */
@RestController
@RequestMapping("/api/v1/chat/skill-delegations")
@RequiredArgsConstructor
public class SkillDelegationController {
    private final SkillDelegations delegations;
    private final ConversationAccess access;
    private final CurrentUserProvider currentUser;


    @GetMapping("/targets")
    public List<SkillDelegationTargetResponse> targets() {
        return delegations.targets(currentUser.require()).stream().map(SkillDelegationTargetResponse::from).toList();
    }


    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SkillDelegationResponse delegate(@Valid @RequestBody SkillDelegationRequest request) {
        CurrentUser user = currentUser.require();
        Long conversationId = request.conversationId() == null ? null : access.requireOwnId(user, request.conversationId());
        return SkillDelegationResponse.from(delegations.delegate(user, conversationId, request.agentCode(), request.text()));
    }
}
