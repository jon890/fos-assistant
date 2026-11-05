package com.bifos.assistant.task.presentation;

import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.task.application.EventWatchService;
import com.bifos.assistant.task.presentation.TaskDtos.EventWatchRequest;
import com.bifos.assistant.task.presentation.TaskDtos.EventWatchResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 에이전트에 붙은 요청자의 연결마다 사건 감지를 읽고 켜고 끈다(ADR-20261105 / event-watch). */
@RestController
@RequestMapping("/api/v1/agents/{code}/event-watches")
@RequiredArgsConstructor
public class EventWatchController {

    private final CurrentUserProvider currentUser;
    private final EventWatchService watches;


    @GetMapping
    public List<EventWatchResponse> list(@PathVariable String code) {
        return watches.list(currentUser.require(), code).stream().map(EventWatchResponse::from).toList();
    }


    @PutMapping("/{connectorId}")
    public EventWatchResponse update(
        @PathVariable String code, @PathVariable String connectorId, @Valid @RequestBody EventWatchRequest request) {
        return EventWatchResponse.from(
            watches.update(currentUser.require(), code, connectorId, request.enabled(), request.timezone()));
    }
}
