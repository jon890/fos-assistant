package com.bifos.assistant.task.presentation;

import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.task.application.ProactiveScheduleService;
import com.bifos.assistant.task.presentation.TaskDtos.ProactiveScheduleRequest;
import com.bifos.assistant.task.presentation.TaskDtos.ProactiveScheduleView;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 에이전트별 매일 먼저 살펴보기 깨우기 설정을 읽고 바꾼다. */
@RestController
@RequestMapping("/api/v1/agents/{code}/proactive-check/schedule")
@RequiredArgsConstructor
public class ProactiveScheduleController {

    private final CurrentUserProvider currentUser;
    private final ProactiveScheduleService schedules;

    @GetMapping
    public ProactiveScheduleView get(@PathVariable String code) {
        return ProactiveScheduleView.from(schedules.get(currentUser.require(), code));
    }

    @PutMapping
    public ProactiveScheduleView update(
            @PathVariable String code, @Valid @RequestBody ProactiveScheduleRequest request) {
        return ProactiveScheduleView.from(
                schedules.update(currentUser.require(), code, request.enabled(), request.time(), request.timezone()));
    }
}
