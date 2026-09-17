package com.bifos.assistant.mcp.presentation;

import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.mcp.presentation.AgentTokenDtos.IssueRequest;
import com.bifos.assistant.mcp.presentation.AgentTokenDtos.IssuedTokenResponse;
import com.bifos.assistant.mcp.presentation.AgentTokenDtos.TokenResponse;
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

@RestController
@RequestMapping("/api/v1/admin/agent-tokens")
@RequiredArgsConstructor
public class AgentTokenAdminController {
    private final AgentTokenService tokens; private final CurrentUserProvider currentUser;
    @PostMapping public IssuedTokenResponse issue(@Valid @RequestBody IssueRequest request) { currentUser.requireAdmin(); return IssuedTokenResponse.from(tokens.issue(request.userEmail(), request.label())); }
    @GetMapping public List<TokenResponse> list() { currentUser.requireAdmin(); return tokens.list().stream().map(TokenResponse::from).toList(); }
    @DeleteMapping("/{id}") public void revoke(@PathVariable Long id) { currentUser.requireAdmin(); tokens.revoke(id); }
}
