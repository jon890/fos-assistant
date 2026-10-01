package com.bifos.assistant.connector.domain;

import com.bifos.assistant.connector.domain.type.ToolApproval;
import com.bifos.assistant.connector.domain.type.ToolRisk;

/**
 * 커넥터 도구 하나에 선언된 정책이다(ADR-049).
 *
 * @param title 사람에게 보일 이름. 선언하지 않았으면 null
 */
public record ToolPolicy(ToolRisk risk, ToolApproval approval, String title) {}
