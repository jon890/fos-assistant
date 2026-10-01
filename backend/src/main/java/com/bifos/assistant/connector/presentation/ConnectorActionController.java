package com.bifos.assistant.connector.presentation;

import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.connector.application.ConnectorActionService;
import com.bifos.assistant.connector.presentation.ConnectorActionDtos.ActionView;
import com.bifos.assistant.connector.presentation.ConnectorActionDtos.ApproveRequest;
import com.bifos.assistant.connector.presentation.ConnectorActionDtos.GrantView;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.error.ErrorResponse;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 승인 줄의 조회와 승인과 거절, 상시 허락의 조회와 거두기다(ADR-048).
 *
 * <p>누구의 줄인지는 로그인에서만 정한다. 남의 줄과 남의 허락은 없는 것과 같은 응답이고 관리자에게도 같다.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ConnectorActionController {
    private final ConnectorActionService actions;
    private final ConversationAccess conversations;
    private final CurrentUserProvider currentUser;

    /** 주소의 번호는 대화의 공개 식별자다. 주인의 대화가 아니면 다른 대화 경로와 같은 응답이다. */
    @GetMapping("/chat/conversations/{conversationId}/connector-actions")
    public List<ActionView> list(@PathVariable UUID conversationId) {
        CurrentUser user = currentUser.require();
        return actions.listForConversation(user, conversations.requireOwnId(user, conversationId)).stream()
                .map(ActionView::from)
                .toList();
    }

    /** 본문이 없으면 상시 허락을 주지 않는 승인이다. */
    @PostMapping("/connector-actions/{actionId}/approve")
    public ActionView approve(@PathVariable UUID actionId, @RequestBody(required = false) ApproveRequest request) {
        return ActionView.from(
                actions.approve(currentUser.require(), actionId, request == null ? null : request.grant()));
    }

    @PostMapping("/connector-actions/{actionId}/reject")
    public ActionView reject(@PathVariable UUID actionId) {
        return ActionView.from(actions.reject(currentUser.require(), actionId));
    }

    @GetMapping("/connector-grants")
    public List<GrantView> grants() {
        return actions.grants(currentUser.require()).stream()
                .map(GrantView::from)
                .toList();
    }

    @DeleteMapping("/connector-grants/{grantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeGrant(@PathVariable Long grantId) {
        actions.revokeGrant(currentUser.require(), grantId);
    }

    /** {@code grant} 가 모르는 글이면 입력 오류다. 다른 입력 오류와 같은 모양으로 답한다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadableRequest() {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(ErrorCode.VALIDATION_FAILED.name(), "invalid connector action request"));
    }
}
