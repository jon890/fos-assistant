package com.bifos.assistant.attention.presentation;

import com.bifos.assistant.attention.application.AttentionControlService;
import com.bifos.assistant.attention.application.AttentionService;
import com.bifos.assistant.attention.presentation.AttentionDtos.EventRequest;
import com.bifos.assistant.attention.presentation.AttentionDtos.HideRequest;
import com.bifos.assistant.attention.presentation.AttentionDtos.RestoreRequest;
import com.bifos.assistant.attention.presentation.AttentionDtos.SnoozeRequest;
import com.bifos.assistant.attention.presentation.AttentionDtos.SummaryResponse;
import com.bifos.assistant.attention.presentation.AttentionDtos.ViewResponse;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 요청자 자신의 지금 화면 카드를 읽고, 그 항목을 숨기거나 미루고, 항목에 한 일을 남기는 경로다. 요청자의 기록만 읽고 쓴다.
 *
 * <p>계약은 {@code docs/features/attention.md} 의 「API(먼저 알리기와 지금 화면의 판정)」 가 갖는다. {@code itemKey} 에 {@code :} 와 UUID 가 들어 있어 경로 대신
 * 본문으로 받는다.
 */
@RestController
@RequestMapping("/api/v1/attention")
@RequiredArgsConstructor
public class AttentionController {

    private final AttentionService attention;
    private final AttentionControlService controls;
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

    @PostMapping("/hide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void hide(@RequestBody HideRequest request) {
        controls.hide(
                currentUser.require(), AttentionDtos.cardOf(request.card()), request.itemKey(), request.stateKey());
    }

    @PostMapping("/snooze")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void snooze(@RequestBody SnoozeRequest request) {
        controls.snooze(
                currentUser.require(), AttentionDtos.cardOf(request.card()), request.itemKey(), request.until());
    }

    @PostMapping("/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restore(@RequestBody RestoreRequest request) {
        controls.restore(currentUser.require(), AttentionDtos.cardOf(request.card()), request.itemKey());
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void record(@RequestBody EventRequest request) {
        controls.record(
                currentUser.require(),
                request.itemKey(),
                request.stateKey(),
                AttentionDtos.eventTypeOf(request.type()));
    }
}
