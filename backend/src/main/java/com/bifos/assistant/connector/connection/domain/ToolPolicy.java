package com.bifos.assistant.connector.connection.domain;

import com.bifos.assistant.connector.connection.domain.type.ToolApproval;
import com.bifos.assistant.connector.connection.domain.type.ToolRisk;
import java.util.Set;

/**
 * 커넥터 도구 하나에 선언된 정책이다(ADR-049).
 *
 * @param title 사람에게 보일 이름. 선언하지 않았으면 null
 * @param grantable 그 도구에 상시 허락을 줄 수 있는가(ADR-065). 거짓이면 유효한 허락이 남아 있어도 호출마다 승인을
 * 받는다
 * @param identifiers 승인 카드가 길이로 가리지 않을 식별자 인자의 이름(ADR-089). 승인을 받는 도구만 가질 수 있다
 * @param conversationGrantable 그 도구를 한 대화에서 계속 허용할 수 있는가(ADR-20261012 / conversation-grant). 선언이 연
 * 도구만 참이다
 */
public record ToolPolicy(
    ToolRisk risk,
    ToolApproval approval,
    String title,
    boolean grantable,
    Set<String> identifiers,
    boolean conversationGrantable) {

    public ToolPolicy {
        identifiers = identifiers == null ? Set.of() : Set.copyOf(identifiers);
    }


    /** 대화 허락을 선언하지 않은 도구다. 대화 허락은 선언한 도구에만 연다. */
    public ToolPolicy(ToolRisk risk, ToolApproval approval, String title, boolean grantable, Set<String> identifiers) {
        this(risk, approval, title, grantable, identifiers, false);
    }


    /** 식별자 인자를 선언하지 않은 도구다. */
    public ToolPolicy(ToolRisk risk, ToolApproval approval, String title, boolean grantable) {
        this(risk, approval, title, grantable, Set.of());
    }
}
