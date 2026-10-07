package com.bifos.assistant.chat.presentation;

import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.presentation.ChatDtos.MemoryCaptureView;
import com.bifos.assistant.memory.application.MemoryCaptureService;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대화에서 에이전트가 남긴 기억 기록을 읽고 되돌린다(ADR-094).
 *
 * <p>받아들이기, 거절, 고치기는 {@code /api/v1/memories/{id}} 의 경로를 그대로 쓴다. 누구의 기록인지는 로그인에서만 정한다.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MemoryCaptureController {
    private final MemoryCaptureService captures;
    private final ConversationAccess conversations;
    private final CurrentUserProvider currentUser;

    /** 주소의 번호는 대화의 공개 식별자다. 주인의 대화가 아니면 다른 대화 경로와 같은 응답이다. */
    @GetMapping("/chat/conversations/{conversationId}/memory-captures")
    public List<MemoryCaptureView> list(@PathVariable UUID conversationId) {
        CurrentUser user = currentUser.require();
        return captures.capturesOf(user, conversations.requireOwnId(user, conversationId)).stream()
                .map(MemoryCaptureView::from)
                .toList();
    }

    /** 새로 만든 항목은 지우고 고친 항목은 고치기 전의 판으로 돌린다. */
    @PostMapping("/memory-captures/{id}/undo")
    public void undo(@PathVariable Long id) {
        captures.undo(currentUser.require(), id);
    }
}
