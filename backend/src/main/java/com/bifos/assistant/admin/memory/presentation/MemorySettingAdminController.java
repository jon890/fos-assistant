package com.bifos.assistant.admin.memory.presentation;

import com.bifos.assistant.admin.memory.presentation.MemoryAdminDtos.MemoryContextSettingView;
import com.bifos.assistant.admin.memory.presentation.MemoryAdminDtos.ReplaceMemoryContextRequest;
import com.bifos.assistant.memory.application.MemoryContextSettings;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리자가 그룹의 기억 요약 구역 예산과 기억 전체 상한을 읽고 바꾸는 API 다({@code docs/features/memory/design.md} 의 「관리자 API(PR1)」). */
@RestController
@RequestMapping("/api/v1/admin/memory-settings")
@RequiredArgsConstructor
public class MemorySettingAdminController {

    private final MemoryContextSettings settings;
    private final CurrentUserProvider currentUser;


    @GetMapping
    public MemoryContextSettingView get() {
        CurrentUser admin = currentUser.requireAdmin();
        return MemoryContextSettingView.of(settings.budgetFor(admin.groupId()), settings);
    }


    @PutMapping
    public MemoryContextSettingView replace(@Valid @RequestBody ReplaceMemoryContextRequest request) {
        CurrentUser admin = currentUser.requireAdmin();
        return MemoryContextSettingView.of(settings.save(admin, request.summaryMaxChars(), request.maxChars()), settings);
    }
}
