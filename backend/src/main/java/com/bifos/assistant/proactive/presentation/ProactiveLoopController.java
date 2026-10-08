package com.bifos.assistant.proactive.presentation;

import com.bifos.assistant.proactive.application.ProactiveLoopSettingService;
import com.bifos.assistant.proactive.presentation.ProactiveCheckDtos.LoopSettingBody;
import com.bifos.assistant.proactive.presentation.ProactiveCheckDtos.LoopSettingResponse;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 에이전트마다 매일 루프를 켜고 끄고 쉬게 한다(ADR-20261008 / daily-loop). */
@RestController
@RequestMapping("/api/v1/agents/{code}/proactive-check/loop")
@RequiredArgsConstructor
public class ProactiveLoopController {

    private final ProactiveLoopSettingService settings;
    private final CurrentUserProvider currentUser;

    /** 설치가 루프를 여는지, 요청자가 켰는지, 쉬는 끝 시각을 준다. */
    @GetMapping
    public LoopSettingResponse get(@PathVariable String code) {
        return LoopSettingResponse.from(settings.get(currentUser.require(), code));
    }

    /** 켜기와 쉬기를 저장하고 조회와 같은 모양을 준다. */
    @PutMapping
    public LoopSettingResponse update(@PathVariable String code, @Valid @RequestBody LoopSettingBody body) {
        return LoopSettingResponse.from(
                settings.update(currentUser.require(), code, body.enabled(), body.snoozedUntil()));
    }
}
