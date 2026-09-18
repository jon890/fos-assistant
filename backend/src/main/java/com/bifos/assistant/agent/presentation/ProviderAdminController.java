package com.bifos.assistant.agent.presentation;

import com.bifos.assistant.agent.application.ProviderBlocklist;
import com.bifos.assistant.agent.presentation.AgentDtos.BlockedProviderView;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 지금 막힌 provider 를 관리 화면에 보인다.
 *
 * <p>막힌 것만 준다. 평소에는 빈 목록이고 화면은 그때 아무것도 그리지 않는다.
 */
@RestController
@RequestMapping("/api/v1/admin/providers")
@RequiredArgsConstructor
public class ProviderAdminController {

    private final CurrentUserProvider currentUser;
    private final ProviderBlocklist blocklist;

    @GetMapping("/blocked")
    public List<BlockedProviderView> blocked() {
        currentUser.requireAdmin();
        Instant now = Instant.now();
        return blocklist.blocked().stream().map(state -> BlockedProviderView.from(state, now)).toList();
    }
}
