package com.bifos.assistant.chat.presentation;

import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.application.ConversationEventHub;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 열린 대화 창이 요청 없이 도는 turn 의 사건을 받는 경로다.
 *
 * <p>사용자가 보낸 turn 은 보낸 연결로 사건을 받으므로 여기에 싣지 않는다. 여기에는 요청한 연결이 없는 turn 의
 * 사건이 온다. 위임 결과로 열린 자동 turn 과 대기 메시지로 연 turn 이 그렇다. 대기 메시지를 사용자 메시지로
 * 저장했다는 {@code user} 사건과 대기 줄이 바뀌었다는 {@code pending} 사건도 여기로 온다.
 */
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ConversationEventController {

    private final CurrentUserProvider currentUser;
    private final ConversationAccess access;
    private final ConversationEventHub hub;
    private final ChatEventStreams streams;

    @GetMapping(path = "/conversations/{conversationId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID conversationId) {
        CurrentUser user = currentUser.require();
        // SSE 를 열기 전에 확인해야 남의 대화에 다른 경로와 같은 404 를 돌려준다.
        Long number = access.requireOwnId(user, conversationId);
        // 도구의 명령 원문은 보내기 직전에 보는 사람에 맞춰 뺀다. 근거는 ADR-038 에 있다.
        return streams.follow(send -> hub.subscribe(number, event -> send.accept(event.forViewer(user))));
    }
}
