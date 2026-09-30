package com.bifos.assistant.skill.presentation;

import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.skill.application.SkillService;
import com.bifos.assistant.skill.presentation.SkillDtos.SkillDetailView;
import com.bifos.assistant.skill.presentation.SkillDtos.SkillListView;
import com.bifos.assistant.skill.presentation.SkillDtos.ToggleSkillRequest;
import com.bifos.assistant.skill.presentation.SkillDtos.WriteSkillRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 에이전트에 올린 스킬을 읽고 쓰고 지우는 경로다.
 *
 * <p>볼 수 없는 에이전트는 없는 에이전트와 같은 응답을 준다. 누가 고칠 수 있는지와 입력 규칙은
 * {@link SkillService} 가 정한다.
 */
@RestController
@RequestMapping("/api/v1/agents/{code}/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skills;
    private final CurrentUserProvider currentUser;

    @GetMapping
    public SkillListView list(@PathVariable String code) {
        return SkillListView.from(skills.list(currentUser.require(), code));
    }

    @GetMapping("/{name}")
    public SkillDetailView read(@PathVariable String code, @PathVariable String name) {
        return SkillDetailView.from(skills.read(currentUser.require(), code, name));
    }

    /** 스킬 하나를 통째로 바꾼다. 없으면 만든다. */
    @PutMapping("/{name}")
    public SkillDetailView write(
            @PathVariable String code, @PathVariable String name, @Valid @RequestBody WriteSkillRequest request) {
        return SkillDetailView.from(
                skills.save(currentUser.require(), code, name, request.skillMd(), request.inputs()));
    }

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String code, @PathVariable String name) {
        skills.delete(currentUser.require(), code, name);
    }

    /** 대시보드의 전역 켜고 끄기를 쓴다. */
    @PutMapping("/{name}/enabled")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void toggle(
            @PathVariable String code, @PathVariable String name, @Valid @RequestBody ToggleSkillRequest request) {
        skills.toggle(currentUser.require(), code, name, request.enabled());
    }
}
