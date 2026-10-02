package com.bifos.assistant.chat.presentation;

import com.bifos.assistant.chat.application.StarterSuggestionService;
import com.bifos.assistant.chat.presentation.ChatDtos.StartersView;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 새 대화 화면이 요청자에게 맞춘 추천 질문을 읽는 경로다.
 *
 * <p>볼 수 없는 에이전트는 없는 에이전트와 같은 응답을 준다. 추천을 언제 만들고 무엇을 돌려줄지는
 * {@link StarterSuggestionService} 가 정한다.
 */
@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentStarterController {

    private final StarterSuggestionService starters;
    private final CurrentUserProvider currentUser;

    @GetMapping("/{code}/starters")
    public StartersView read(@PathVariable String code) {
        return StartersView.from(starters.read(currentUser.require(), code));
    }
}
