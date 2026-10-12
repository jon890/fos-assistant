package com.bifos.assistant.connector.application.model;

import com.bifos.assistant.connector.domain.ConnectorConversationGrant;
import java.time.Instant;

/**
 * 한 대화에서 계속 허용 중인 도구 하나다(ADR-20261012 / conversation-grant). 도구의 원래 이름은 내부 값이라 싣지 않는다.
 *
 * @param title 사람에게 보일 이름. 카탈로그의 선언에 이름이 없거나 읽지 못했으면 고정 문구다
 */
public record ConnectorConversationGrantView(Long grantId, String title, Instant expiresAt) {

    /** @param declaredTitle 카탈로그가 선언한 이름. 없거나 읽지 못했으면 null */
    public static ConnectorConversationGrantView from(ConnectorConversationGrant grant, String declaredTitle) {
        return new ConnectorConversationGrantView(
                grant.id(),
                declaredTitle == null || declaredTitle.isBlank() ? ConnectorActionView.UNNAMED_TITLE : declaredTitle,
                grant.expiresAt());
    }
}
