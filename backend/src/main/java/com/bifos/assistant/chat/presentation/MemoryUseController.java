package com.bifos.assistant.chat.presentation;

import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.application.MemoryUseService;
import com.bifos.assistant.chat.presentation.ChatDtos.MemoryUseView;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대화의 답마다 그 실행이 본문을 받은 기억을 읽는다(ADR-20261008 / memory-facts).
 *
 * <p>누구의 대화인지는 로그인에서만 정한다. 응답은 제목과 범위만 싣고 본문은 싣지 않는다.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MemoryUseController {
    private final MemoryUseService uses;
    private final ConversationAccess conversations;
    private final CurrentUserProvider currentUser;

    /** 주소의 번호는 대화의 공개 식별자다. 주인의 대화가 아니면 다른 대화 경로와 같은 응답이다. */
    @GetMapping("/chat/conversations/{conversationId}/memory-uses")
    public List<MemoryUseView> list(@PathVariable UUID conversationId) {
        CurrentUser user = currentUser.require();
        return uses.usesOf(user, conversations.requireOwnId(user, conversationId)).stream()
                .map(MemoryUseView::from)
                .toList();
    }
}
