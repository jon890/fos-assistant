package com.bifos.assistant.attention.presentation;

import com.bifos.assistant.attention.application.AttentionService;
import com.bifos.assistant.attention.presentation.AttentionDtos.SummaryResponse;
import com.bifos.assistant.attention.presentation.AttentionDtos.ViewResponse;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 요청자 자신의 지금 화면 카드를 읽는 경로다. 요청자의 기록만 읽는다.
 *
 * <p>계약은 {@code docs/backend/attention.md} 의 「API」 가 갖는다.
 */
@RestController
@RequestMapping("/api/v1/attention")
@RequiredArgsConstructor
public class AttentionController {

    private final AttentionService attention;
    private final CurrentUserProvider currentUser;

    @GetMapping
    public ViewResponse view() {
        return ViewResponse.from(attention.view(currentUser.require()));
    }

    /** 사이드바와 홈의 한 줄이 읽는 건수다. */
    @GetMapping("/summary")
    public SummaryResponse summary() {
        return new SummaryResponse(attention.summary(currentUser.require()));
    }
}
