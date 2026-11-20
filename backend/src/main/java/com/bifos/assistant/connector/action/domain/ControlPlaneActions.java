package com.bifos.assistant.connector.action.domain;

import com.bifos.assistant.connector.action.domain.type.ActionDecision;
import com.bifos.assistant.connector.connection.domain.ToolPolicy;
import com.bifos.assistant.connector.connection.domain.type.ToolApproval;
import com.bifos.assistant.connector.connection.domain.type.ToolRisk;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 플러그인 도구가 아니라 Control Plane 이 하는 동작의 승인 줄이다(서비스 개념 설계의 「지우기 승인 카드」).
 *
 * <p>같은 {@code connector_action} 표와 카드 흐름을 쓰되, 커넥터 연결 없이 만들고 카탈로그 대신 여기의 정책으로 그린다. 상시 허락과
 * 대화 허락이 없고, 승인 뒤 결과는 다음 turn 이 아니라 알림 줄로만 남긴다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ControlPlaneActions {
    /** 승인 줄의 {@code connector_id} 다. Control Plane MCP 서버 이름과 같고 카탈로그에 없다. */
    public static final String CONNECTOR_ID = "fos-assistant";
    /** 예약 작업 지우기다. MCP 도구 이름과 같다. */
    public static final String TASK_DELETE = "task_delete";
    /** 등록 이름의 앞붙임이다. Hermes 는 이 이름을 쓰지 않는다. */
    private static final String HERMES_PREFIX = "fos_";

    /** 승인 줄의 판정이다. 늘 승인을 받는 쓰기라 상시 허락과 대화 허락이 없다. */
    public static final ToolPolicyDecision DECISION = new ToolPolicyDecision(ActionDecision.NEEDS_APPROVAL, null, ToolRisk.WRITE, ToolApproval.ALWAYS);

    private static final Map<String, ToolPolicy> POLICIES = Map.of(
        TASK_DELETE, new ToolPolicy(ToolRisk.WRITE, ToolApproval.ALWAYS, "예약 작업 지우기", false, Set.of("task_id", "task", "next_run")));

    private static final Map<String, Map<String, String>> LABELS = Map.of(
        TASK_DELETE, Map.of("task", "작업", "next_run", "다음 실행", "task_id", "작업 번호"));


    /** 그 동작의 등록 이름이다. */
    public static String hermesTool(String toolName) {
        return HERMES_PREFIX + toolName;
    }


    /** 그 줄이 Control Plane 승인 줄인가. 연결 번호, 도구 이름, 등록 이름이 모두 맞아야 한다. */
    public static boolean owns(ConnectorAction action) {
        return CONNECTOR_ID.equals(action.connectorId())
            && action.toolName() != null
            && POLICIES.containsKey(action.toolName())
            && hermesTool(action.toolName()).equals(action.hermesTool());
    }


    /** 그 동작의 카드 정책이다. 모르는 이름이면 빈 값이다. */
    public static Optional<ToolPolicy> policy(String toolName) {
        return Optional.ofNullable(toolName == null ? null : POLICIES.get(toolName));
    }


    /** 카드가 인자 이름 대신 보일 글이다. */
    public static Map<String, String> labels(String toolName) {
        return toolName == null ? Map.of() : LABELS.getOrDefault(toolName, Map.of());
    }
}
