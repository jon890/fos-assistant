package com.bifos.assistant.agent.presentation;

import com.bifos.assistant.agent.application.StarterService;
import com.bifos.assistant.agent.presentation.AgentDtos.StartersView;
import com.bifos.assistant.agent.presentation.AgentDtos.WriteStartersRequest;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 에이전트의 한 줄 소개와 추천 질문을 읽고 쓰는 경로다.
 *
 * <p>볼 수 없는 에이전트는 없는 에이전트와 같은 응답을 준다. 누가 고칠 수 있는지와 줄을 다듬는 규칙은
 * {@link StarterService} 가 정한다.
 */
@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentStarterController {

    private final StarterService starters;
    private final CurrentUserProvider currentUser;

    @GetMapping("/{code}/starters")
    public StartersView read(@PathVariable String code) {
        return StartersView.from(starters.read(currentUser.require(), code));
    }

    @PutMapping("/{code}/starters")
    public StartersView write(
            @PathVariable String code, @Valid @RequestBody WriteStartersRequest request) {
        return StartersView.from(starters.write(
                currentUser.require(), code, request.tagline(), request.starterPrompts()));
    }
}
