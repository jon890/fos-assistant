package com.bifos.assistant.user.presentation;

import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MeController {
    private final CurrentUserProvider currentUser;

    @GetMapping
    public MeView read() {
        return MeView.from(currentUser.require());
    }

    public record MeView(Long id, String email, String displayName, String role) {
        static MeView from(CurrentUser user) {
            return new MeView(user.id(), user.email(), user.displayName(), user.role().name());
        }
    }
}
