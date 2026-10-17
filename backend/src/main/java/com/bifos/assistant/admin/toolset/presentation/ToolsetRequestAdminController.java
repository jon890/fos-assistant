package com.bifos.assistant.admin.toolset.presentation;

import com.bifos.assistant.agent.application.toolset.ToolsetRequestService;
import com.bifos.assistant.admin.toolset.presentation.ToolsetAdminDtos.ToolsetRequestResponse;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리자가 같은 그룹의 toolset 요청을 읽고 결정한다. */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class ToolsetRequestAdminController {
    private final ToolsetRequestService requests;
    private final CurrentUserProvider currentUser;


    @GetMapping("/agents/{code}/tool-requests")
    public List<ToolsetRequestResponse> list(@PathVariable String code) {
        return requests.list(currentUser.requireAdmin(), code, true).stream()
            .map(ToolsetRequestResponse::from)
            .toList();
    }


    @GetMapping("/tool-requests/{id}")
    public ToolsetRequestResponse read(@PathVariable UUID id) {
        return ToolsetRequestResponse.from(requests.read(currentUser.requireAdmin(), id, true));
    }


    @PostMapping("/tool-requests/{id}")
    public ToolsetRequestResponse decide(
        @PathVariable UUID id, @Valid @RequestBody ToolsetAdminDtos.DecideToolsetRequest body) {
        return ToolsetRequestResponse.from(
            requests.decide(currentUser.requireAdmin(), id, body.approve(), body.reason()));
    }
}
