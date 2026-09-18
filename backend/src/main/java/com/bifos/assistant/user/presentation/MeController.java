package com.bifos.assistant.user.presentation;

import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.user.presentation.UserDtos.MeView;
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
}
