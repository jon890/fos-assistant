package com.bifos.assistant.chat.turn.presentation;

import com.bifos.assistant.chat.conversation.application.ConversationAccess;
import com.bifos.assistant.chat.conversation.application.MemoryUseService;
import com.bifos.assistant.chat.turn.presentation.ChatDtos.MemoryUseReactionRequest;
import com.bifos.assistant.chat.turn.presentation.ChatDtos.MemoryUseView;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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


    /**
     * 「참고한 기억」 항목의 「관련 없음」, 「틀림」 단추다(ADR-20261015 / memory-maintenance). 사건 하나만 남기고 기억 내용은 바꾸지 않는다. 같은
     * 단추를 다시 누르면 사건이 하나 더 남는다.
     */
    @PostMapping("/chat/conversations/{conversationId}/memory-uses/reactions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void react(@PathVariable UUID conversationId, @Valid @RequestBody MemoryUseReactionRequest request) {
        CurrentUser user = currentUser.require();
        uses.react(
            user,
            conversations.requireOwnId(user, conversationId),
            request.executionId(),
            request.memoryId(),
            request.reaction());
    }
}
