package com.bifos.assistant.agent.presentation;

import com.bifos.assistant.agent.application.ToolsetRequestService;
import com.bifos.assistant.agent.presentation.AgentDtos.ToolsetRequestResponse;
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

/** 요청자는 자기 요청을, 관리자는 같은 그룹의 요청을 읽고 결정한다. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ToolsetRequestController {
    private final ToolsetRequestService requests;
    private final CurrentUserProvider currentUser;

    @GetMapping("/agents/{code}/tool-requests")
    public List<ToolsetRequestResponse> list(@PathVariable String code) {
        return requests.list(currentUser.require(), code, false).stream()
                .map(ToolsetRequestResponse::from)
                .toList();
    }

    @PostMapping("/agents/{code}/tool-requests")
    public ToolsetRequestResponse request(
            @PathVariable String code, @Valid @RequestBody AgentDtos.RequestToolset body) {
        return ToolsetRequestResponse.from(requests.request(currentUser.require(), code, body.toolset()));
    }

    @GetMapping("/admin/agents/{code}/tool-requests")
    public List<ToolsetRequestResponse> listAdmin(@PathVariable String code) {
        return requests.list(currentUser.requireAdmin(), code, true).stream()
                .map(ToolsetRequestResponse::from)
                .toList();
    }

    @GetMapping("/tool-requests/{id}")
    public ToolsetRequestResponse read(@PathVariable UUID id) {
        return ToolsetRequestResponse.from(requests.read(currentUser.require(), id, false));
    }

    @GetMapping("/admin/tool-requests/{id}")
    public ToolsetRequestResponse readAdmin(@PathVariable UUID id) {
        return ToolsetRequestResponse.from(requests.read(currentUser.requireAdmin(), id, true));
    }

    @PostMapping("/admin/tool-requests/{id}")
    public ToolsetRequestResponse decide(
            @PathVariable UUID id, @Valid @RequestBody AgentDtos.DecideToolsetRequest body) {
        return ToolsetRequestResponse.from(
                requests.decide(currentUser.requireAdmin(), id, body.approve(), body.reason()));
    }

    @PostMapping("/tool-requests/{id}/cancel")
    public ToolsetRequestResponse cancel(@PathVariable UUID id) {
        return ToolsetRequestResponse.from(requests.cancel(currentUser.require(), id));
    }
}
