package com.bifos.assistant.notification.presentation;

import com.bifos.assistant.notification.application.NotificationEventHub;
import com.bifos.assistant.notification.application.NotificationService;
import com.bifos.assistant.notification.presentation.NotificationDtos.NotificationPageView;
import com.bifos.assistant.notification.presentation.NotificationDtos.NotificationView;
import com.bifos.assistant.notification.presentation.NotificationDtos.UnreadCountView;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 로그인한 사용자 자신의 알림을 다루는 경로다(ADR-070). 요청 본문이 받는 사람을 정하지 못한다.
 *
 * <p>계약은 {@code docs/backend/notification.md} 의 「API」 가 갖는다.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final CurrentUserProvider currentUser;
    private final NotificationService notifications;
    private final NotificationEventHub hub;
    private final NotificationEventStreams streams;

    /** 최근 것부터 한 쪽 돌려준다. 다음 쪽은 {@code nextCursor} 를 {@code cursor} 로 넘겨 읽는다. */
    @GetMapping
    public NotificationPageView list(
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "30") int limit) {
        return NotificationPageView.from(notifications.page(currentUser.require(), cursor, limit));
    }

    @PostMapping("/{notificationId}/read")
    public NotificationView read(@PathVariable UUID notificationId) {
        return NotificationView.from(notifications.markRead(currentUser.require(), notificationId));
    }

    @PostMapping("/read-all")
    public UnreadCountView readAll() {
        return new UnreadCountView(notifications.markAllRead(currentUser.require()));
    }

    /** 그 사용자의 알림 사건을 받는다. 창마다 구독이 하나다. */
    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        CurrentUser user = currentUser.require();
        return streams.follow(send -> hub.subscribe(user.id(), send));
    }
}
