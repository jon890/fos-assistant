package com.bifos.assistant.testsupport;

import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.followup.application.FollowUpService;
import com.bifos.assistant.followup.domain.FollowUp;
import com.bifos.assistant.followup.infra.FollowUpRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 브라우저 검사에서 에이전트가 제안한 할 일({@code PROPOSED})을 만든다.
 *
 * <p>사람이 쓰는 할 일 경로는 바로 {@code OPEN} 으로 만들고, 제안은 에이전트의 제안 도구만 만든다.
 * 브라우저 검사는 그 도구를 부를 수 없어, 운영 코드에 문을 더하지 않고 여기서 저장소로 직접 넣는다.
 */
@RestController
@RequestMapping("/api/v1/test-support/follow-ups")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "assistant.test-support.enabled", havingValue = "true")
public class FollowUpTestSupportController {

    private final CurrentUserProvider currentUser;
    private final ConversationAccess conversations;
    private final FollowUpRepository followUps;
    private final EntityManager entityManager;
    private final Clock clock;

    /**
     * 부르는 사람의 대화에 제안을 하나 넣는다. 제안한 실행은 그 대화의 마지막 실행으로 둔다.
     *
     * <p>남의 대화나 없는 대화면 운영 경로와 같은 404 {@code CONVERSATION_NOT_FOUND} 다.
     */
    @PostMapping("/proposed")
    public ProposedFollowUp proposed(@RequestBody ProposedRequest request) {
        CurrentUser user = currentUser.require();
        String title = Objects.requireNonNull(request.title(), "title");
        Long conversationId = conversations.requireOwnId(user, request.conversationId());
        Long executionId = entityManager
                .createQuery("select max(e.id) from AgentExecution e where e.conversationId = :id", Long.class)
                .setParameter("id", conversationId)
                .getSingleResult();
        FollowUp saved = followUps.saveAndFlush(FollowUp.proposed(
                user.id(),
                conversationId,
                executionId,
                title.strip(),
                FollowUpService.titleKey(title),
                null,
                false,
                clock.instant()));
        return new ProposedFollowUp(saved.publicId());
    }

    /** 제안할 대화의 공개 식별자와 할 일 제목이다. */
    public record ProposedRequest(UUID conversationId, String title) {}

    /** 만든 할 일의 공개 식별자다. */
    public record ProposedFollowUp(UUID id) {}
}
