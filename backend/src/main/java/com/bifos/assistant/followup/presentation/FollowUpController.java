package com.bifos.assistant.followup.presentation;

import com.bifos.assistant.followup.application.FollowUpService;
import com.bifos.assistant.followup.application.model.FollowUpPatch;
import com.bifos.assistant.followup.application.model.NewFollowUp;
import com.bifos.assistant.followup.presentation.FollowUpDtos.CreateFollowUpRequest;
import com.bifos.assistant.followup.presentation.FollowUpDtos.FollowUpView;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 사람이 웹에서 쓰는 할 일 경로다. 계약은 {@code docs/backend/follow-up.md} 의 「API」 가 갖는다.
 *
 * <p>주인은 웹 토큰의 사용자다. 요청 본문으로 사용자를 받지 않는다.
 */
@RestController
@RequestMapping("/api/v1/follow-ups")
@RequiredArgsConstructor
public class FollowUpController {
    private final FollowUpService followUps;
    private final CurrentUserProvider currentUser;

    @GetMapping
    public List<FollowUpView> list() {
        return followUps.list(currentUser.require()).stream()
                .map(FollowUpView::from)
                .toList();
    }

    @PostMapping
    public FollowUpView create(@RequestBody CreateFollowUpRequest request) {
        NewFollowUp input = new NewFollowUp(
                request.title(),
                request.dueAt() == null ? null : parseInstant(request.dueAt()),
                Boolean.TRUE.equals(request.waiting()),
                request.conversationId() == null ? null : parseConversationId(request.conversationId()));
        return FollowUpView.from(followUps.create(currentUser.require(), input));
    }

    /**
     * 본문에 있는 칸만 고친다. {@code dueAt} 은 키가 있는지와 값이 null 인지를 갈라야 해서 본문을 노드로 받는다.
     *
     * <p>{@code title} 과 {@code waiting} 은 키가 없거나 null 이면 그대로 둔다. 그 밖의 키는 읽지 않는다.
     */
    @PatchMapping("/{id}")
    public FollowUpView update(@PathVariable UUID id, @RequestBody JsonNode body) {
        if (body == null || !body.isObject()) {
            throw invalid("body must be a JSON object");
        }
        String title = null;
        JsonNode titleNode = body.get("title");
        if (titleNode != null && !titleNode.isNull()) {
            if (!titleNode.isString()) {
                throw invalid("title must be a string");
            }
            title = titleNode.asString();
        }
        Boolean waiting = null;
        JsonNode waitingNode = body.get("waiting");
        if (waitingNode != null && !waitingNode.isNull()) {
            if (!waitingNode.isBoolean()) {
                throw invalid("waiting must be a boolean");
            }
            waiting = waitingNode.asBoolean();
        }
        boolean dueAtPresent = body.has("dueAt");
        Instant dueAt = null;
        JsonNode dueAtNode = body.get("dueAt");
        if (dueAtNode != null && !dueAtNode.isNull()) {
            if (!dueAtNode.isString()) {
                throw invalid("dueAt must be an ISO-8601 instant with offset");
            }
            dueAt = parseInstant(dueAtNode.asString());
        }
        return FollowUpView.from(
                followUps.update(currentUser.require(), id, new FollowUpPatch(title, dueAtPresent, dueAt, waiting)));
    }

    @PostMapping("/{id}/accept")
    public FollowUpView accept(@PathVariable UUID id) {
        return FollowUpView.from(followUps.accept(currentUser.require(), id));
    }

    @PostMapping("/{id}/reject")
    public FollowUpView reject(@PathVariable UUID id) {
        return FollowUpView.from(followUps.reject(currentUser.require(), id));
    }

    @PostMapping("/{id}/done")
    public FollowUpView done(@PathVariable UUID id) {
        return FollowUpView.from(followUps.done(currentUser.require(), id));
    }

    @PostMapping("/{id}/drop")
    public FollowUpView drop(@PathVariable UUID id) {
        return FollowUpView.from(followUps.drop(currentUser.require(), id));
    }

    /** {@code 2026-10-05T09:00:00Z} 나 {@code 2026-10-05T18:00:00+09:00} 처럼 시간대가 붙은 시각을 읽는다. */
    private static Instant parseInstant(String text) {
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "dueAt must be an ISO-8601 instant with offset", ex);
        }
    }

    private static UUID parseConversationId(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "conversationId must be a UUID", ex);
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }
}
