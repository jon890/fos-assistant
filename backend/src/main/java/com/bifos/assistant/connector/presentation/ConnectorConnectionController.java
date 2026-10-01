package com.bifos.assistant.connector.presentation;

import com.bifos.assistant.connector.application.ConnectorConnectionService;
import com.bifos.assistant.connector.presentation.ConnectionDtos.ConnectionView;
import com.bifos.assistant.connector.presentation.ConnectionDtos.ConnectorView;
import com.bifos.assistant.connector.presentation.ConnectionDtos.OptionView;
import com.bifos.assistant.connector.presentation.ConnectionDtos.ValuesRequest;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.error.ErrorResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 연결할 사용자는 로그인에서만 정한다. 요청 본문은 칸 값만 준다. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ConnectorConnectionController {
    private final ConnectorConnectionService connections;
    private final CurrentUserProvider currentUser;

    @GetMapping("/connectors")
    public List<ConnectorView> catalog() {
        return connections.catalog(currentUser.require()).stream()
                .map(ConnectorView::from)
                .toList();
    }

    @GetMapping("/connections/{id}")
    public ConnectionView read(@PathVariable String id) {
        return ConnectionView.from(connections.read(currentUser.require(), id));
    }

    @PostMapping("/connections/{id}/options/{fieldKey}")
    public List<OptionView> options(
            @PathVariable String id, @PathVariable String fieldKey, @RequestBody ValuesRequest request) {
        CurrentUser user = currentUser.require();
        return connections.options(user, id, fieldKey, request.values()).stream()
                .map(OptionView::from)
                .toList();
    }

    @PostMapping("/connections/{id}")
    public ConnectionView register(@PathVariable String id, @RequestBody ValuesRequest request) {
        CurrentUser user = currentUser.require();
        return ConnectionView.from(connections.register(user, id, request.values()));
    }

    @PostMapping("/connections/{id}/check")
    public ConnectionView check(@PathVariable String id) {
        return ConnectionView.from(connections.check(currentUser.require(), id));
    }

    @DeleteMapping("/connections/{id}")
    public ConnectionView disconnect(@PathVariable String id) {
        return ConnectionView.from(connections.disconnect(currentUser.require(), id));
    }

    /** 역직렬화 오류에도 비밀값이 포함된 예외 본문을 응답과 로그에 남기지 않는다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadableRequest() {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(ErrorCode.VALIDATION_FAILED.name(), "invalid connector request"));
    }
}
