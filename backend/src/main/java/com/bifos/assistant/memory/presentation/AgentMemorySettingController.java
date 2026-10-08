package com.bifos.assistant.memory.presentation;

import com.bifos.assistant.memory.application.AgentMemorySettingService;
import com.bifos.assistant.memory.presentation.MemoryDtos.AgentMemoryGrantBody;
import com.bifos.assistant.memory.presentation.MemoryDtos.AgentMemorySettingView;
import com.bifos.assistant.memory.presentation.MemoryDtos.ReplaceAgentMemoryRequest;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리자가 에이전트의 Memory collection 과 민감 허용을 읽고 바꾸는 API 다(ADR-20261008 / agent-memory-grants-admin). */
@RestController
@RequestMapping("/api/v1/admin/agents/{code}/memory-collections")
@RequiredArgsConstructor
public class AgentMemorySettingController {

    private final AgentMemorySettingService settings;
    private final CurrentUserProvider currentUser;

    @GetMapping
    public AgentMemorySettingView get(@PathVariable String code) {
        return AgentMemorySettingView.from(settings.settingOf(currentUser.requireAdmin(), code));
    }

    @PutMapping
    public AgentMemorySettingView replace(
            @PathVariable String code, @Valid @RequestBody ReplaceAgentMemoryRequest request) {
        return AgentMemorySettingView.from(settings.replace(
                currentUser.requireAdmin(),
                code,
                request.collections().stream()
                        .map(AgentMemoryGrantBody::toInput)
                        .toList()));
    }
}
