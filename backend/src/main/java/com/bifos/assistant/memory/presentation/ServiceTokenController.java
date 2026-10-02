package com.bifos.assistant.memory.presentation;

import com.bifos.assistant.memory.application.ServiceTokenService;
import com.bifos.assistant.memory.application.model.IssuedServiceToken;
import com.bifos.assistant.memory.presentation.MemoryDtos.IssueServiceTokenRequest;
import com.bifos.assistant.memory.presentation.MemoryDtos.IssuedServiceTokenView;
import com.bifos.assistant.memory.presentation.MemoryDtos.ServiceTokenGrantBody;
import com.bifos.assistant.memory.presentation.MemoryDtos.ServiceTokenView;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 토큰을 발급하고 목록을 보고 폐기한다(ADR-056). 웹의 JWT 로 부르고 늘 요청자 자신의 토큰만 다룬다.
 * 관리자도 다른 사용자의 토큰을 다루지 못한다.
 */
@RestController
@RequestMapping("/api/v1/service-tokens")
@RequiredArgsConstructor
public class ServiceTokenController {

    private final ServiceTokenService tokens;
    private final CurrentUserProvider currentUser;

    @PostMapping
    public IssuedServiceTokenView issue(@Valid @RequestBody IssueServiceTokenRequest request) {
        IssuedServiceToken issued = tokens.issue(
                currentUser.require(),
                request.label(),
                request.expiresInDays(),
                request.collections().stream()
                        .map(ServiceTokenGrantBody::toGrant)
                        .toList());
        return new IssuedServiceTokenView(ServiceTokenView.from(issued.snapshot()), issued.rawToken());
    }

    @GetMapping
    public List<ServiceTokenView> list() {
        return tokens.listOf(currentUser.require()).stream()
                .map(ServiceTokenView::from)
                .toList();
    }

    @DeleteMapping("/{id}")
    public void revoke(@PathVariable Long id) {
        tokens.revoke(currentUser.require(), id);
    }
}
