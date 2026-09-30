package com.bifos.assistant.connector.presentation;

import com.bifos.assistant.connector.application.AccountbookConnectionService;
import com.bifos.assistant.connector.presentation.ConnectionDtos.ConnectionView;
import com.bifos.assistant.connector.presentation.ConnectionDtos.RegisterRequest;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/connections/accountbook")
@RequiredArgsConstructor
public class AccountbookConnectionController {
    private final AccountbookConnectionService connections;
    private final CurrentUserProvider currentUser;
    @GetMapping public ConnectionView read() { return ConnectionView.from(connections.read(currentUser.require())); }
    @PostMapping public ConnectionView register(@Valid @RequestBody RegisterRequest request) {
        CurrentUser user = currentUser.require(); return ConnectionView.from(connections.register(user, request.token(), request.familyUuid()));
    }
    @PostMapping("/check") public ConnectionView check() { return ConnectionView.from(connections.check(currentUser.require())); }
    @DeleteMapping public ConnectionView disconnect() { return ConnectionView.from(connections.disconnect(currentUser.require())); }
    /** 역직렬화 오류에도 토큰이 포함된 예외 본문을 로그에 남기지 않는다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadableRequest() {
        return ResponseEntity.badRequest().body(new ErrorResponse("VALIDATION_FAILED", "invalid accountbook request"));
    }
}
