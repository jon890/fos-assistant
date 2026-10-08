package com.bifos.assistant.agent.presentation;

import com.bifos.assistant.agent.application.ToolsetCatalogService;
import com.bifos.assistant.agent.application.ToolsetVisibilityService;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 관리자가 도구 목록의 보임과 숨김을 정한다. */
@RestController
@RequestMapping("/api/v1/admin/toolsets")
@RequiredArgsConstructor
public class ToolsetAdminController {
    private final CurrentUserProvider currentUser;
    private final ToolsetCatalogService catalog;
    private final ToolsetVisibilityService visibility;

    @GetMapping
    public List<AgentDtos.CatalogToolsetView> read() {
        return catalog.read(currentUser.requireAdmin()).stream()
                .map(AgentDtos.CatalogToolsetView::from)
                .toList();
    }

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(@Valid @RequestBody AgentDtos.HiddenToolsetsRequest request) {
        visibility.save(currentUser.requireAdmin(), request.hidden());
    }
}
