package com.bifos.assistant.skill.presentation;

import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.skill.application.SkillService;
import com.bifos.assistant.skill.presentation.SkillDtos.SkillListView;
import com.bifos.assistant.skill.presentation.SkillDtos.ToggleSkillRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 영역에서 기본 스킬까지 읽고 켜고 끄는 경로다. */
@RestController
@RequestMapping("/api/v1/admin/agents/{code}/skills")
@RequiredArgsConstructor
public class SkillAdminController {
    private final SkillService skills;
    private final CurrentUserProvider currentUser;

    @GetMapping
    public SkillListView list(@PathVariable String code) {
        return SkillListView.from(skills.adminList(currentUser.requireAdmin(), code));
    }

    @PutMapping("/{name}/enabled")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void toggle(
            @PathVariable String code, @PathVariable String name, @Valid @RequestBody ToggleSkillRequest request) {
        skills.adminToggle(currentUser.requireAdmin(), code, name, request.enabled());
    }
}
