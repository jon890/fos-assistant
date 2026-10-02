package com.bifos.assistant.connector.application.model;

import com.bifos.assistant.connector.domain.type.ToolApproval;
import com.bifos.assistant.connector.domain.type.ToolRisk;

/**
 * 화면이 커넥터의 도구와 그 위험도를 보이는 데 쓰는 선언이다.
 *
 * @param title 사람에게 보일 이름. 선언하지 않았으면 null 이고 화면이 도구 이름을 보인다
 * @param grant 그 도구에 상시 허락을 줄 수 있는가(ADR-064)
 */
public record ConnectorToolSummary(String name, String title, ToolRisk risk, ToolApproval approval, boolean grant) {}
