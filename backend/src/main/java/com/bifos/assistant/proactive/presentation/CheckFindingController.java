package com.bifos.assistant.proactive.presentation;

import com.bifos.assistant.proactive.application.CheckFindingReactions;
import com.bifos.assistant.proactive.application.model.FindingReaction;
import com.bifos.assistant.proactive.presentation.ProactiveCheckDtos.CheckFindingsResponse;
import com.bifos.assistant.proactive.presentation.ProactiveCheckDtos.FindingReactionRequest;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 점검 대화의 「새로 알릴 것」 발견을 읽고 반응을 받는다(ADR-20261008 / check-finding-reaction).
 *
 * <p>누구의 발견인지는 로그인에서만 정한다. 남의 대화와 남의 발견은 없는 것과 같은 응답이다.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CheckFindingController {

    private final CheckFindingReactions reactions;
    private final CurrentUserProvider currentUser;

    /** 주소의 번호는 대화의 공개 식별자다. */
    @GetMapping("/chat/conversations/{conversationId}/check-findings")
    public CheckFindingsResponse list(@PathVariable UUID conversationId) {
        return new CheckFindingsResponse(
                reactions.dismissWindowDays(), reactions.list(currentUser.require(), conversationId));
    }

    @PutMapping("/check-findings/{findingId}/reaction")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void react(@PathVariable Long findingId, @Valid @RequestBody FindingReactionRequest request) {
        reactions.react(currentUser.require(), findingId, FindingReaction.parse(request.reaction()));
    }
}
