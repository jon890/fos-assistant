package com.bifos.assistant.connector.application.model;

import com.bifos.assistant.connector.domain.type.ToolApproval;
import com.bifos.assistant.connector.domain.type.ToolRisk;

/**
 * 화면이 커넥터의 도구와 그 위험도를 보이는 데 쓰는 선언이다.
 *
 * <p>모델이 읽는 도구 설명은 담지 않는다. 화면은 사람 말 제목과 요약만 보인다.
 *
 * @param title 사람에게 보일 이름. 선언하지 않았으면 null 이고 화면이 대체 문구를 보인다
 * @param grant 그 도구에 상시 허락을 줄 수 있는가(ADR-065)
 * @param summary 화면에 보일 한두 문장의 요약. 선언하지 않았으면 null
 */
public record ConnectorToolSummary(String name, String title, ToolRisk risk, ToolApproval approval, boolean grant, String summary) {
    /** 요약이 없는 옛 도구 선언이다. */
    public ConnectorToolSummary(String name, String title, ToolRisk risk, ToolApproval approval, boolean grant) {
        this(name, title, risk, approval, grant, null);
    }
}
