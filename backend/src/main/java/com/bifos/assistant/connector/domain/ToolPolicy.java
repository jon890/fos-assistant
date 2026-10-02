package com.bifos.assistant.connector.domain;

import com.bifos.assistant.connector.domain.type.ToolApproval;
import com.bifos.assistant.connector.domain.type.ToolRisk;

/**
 * 커넥터 도구 하나에 선언된 정책이다(ADR-049).
 *
 * @param title 사람에게 보일 이름. 선언하지 않았으면 null
 * @param grantable 그 도구에 상시 허락을 줄 수 있는가(ADR-064). 거짓이면 유효한 허락이 남아 있어도 호출마다 승인을
 *     받는다
 */
public record ToolPolicy(ToolRisk risk, ToolApproval approval, String title, boolean grantable) {}
