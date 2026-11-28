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
 * 플러그인 도구가 아니라 Control Plane 이 하는 동작의 승인 줄이다(서비스 개념 설계의 「지우기 승인 카드」, 「PR3 할 일과 기억 도구」, 「PR4 연결 붙이기 승인 카드」).
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
    /** 기억 지우기다. MCP 도구 이름과 같다. */
    public static final String MEMORY_FORGET = "memory_forget";
    /** 연결을 새 전문 에이전트를 만들어 붙이기다. MCP 도구 이름과 같다(비서 중심 대화 스펙 REQ9). */
    public static final String CONNECTOR_ATTACH = "connector_attach";
    /** 등록 이름의 앞붙임이다. Hermes 는 이 이름을 쓰지 않는다. */
    private static final String HERMES_PREFIX = "fos_";

    /** 승인 줄의 판정이다. 늘 승인을 받는 쓰기라 상시 허락과 대화 허락이 없다. */
    public static final ToolPolicyDecision DECISION = new ToolPolicyDecision(ActionDecision.NEEDS_APPROVAL, null, ToolRisk.WRITE, ToolApproval.ALWAYS);

    private static final Map<String, ToolPolicy> POLICIES = Map.of(
        TASK_DELETE, new ToolPolicy(ToolRisk.WRITE, ToolApproval.ALWAYS, "예약 작업 지우기", false, Set.of("task_id", "task", "next_run")),
        MEMORY_FORGET, new ToolPolicy(ToolRisk.WRITE, ToolApproval.ALWAYS, "기억 지우기", false, Set.of("memory_id", "revision")),
        CONNECTOR_ATTACH,
        new ToolPolicy(ToolRisk.WRITE, ToolApproval.ALWAYS, "새 전문 에이전트를 만들어 붙이기", false, Set.of("connector_id", "agent_name")));

    private static final Map<String, String> SCREENS = Map.of(
        TASK_DELETE, "예약 작업 화면", MEMORY_FORGET, "기억 화면", CONNECTOR_ATTACH, "외부 서비스 연결 화면");

    private static final Map<String, Map<String, String>> LABELS = Map.of(
        TASK_DELETE, Map.of("task", "작업", "next_run", "다음 실행", "task_id", "작업 번호"),
        MEMORY_FORGET, Map.of("memory", "기억", "content", "내용", "scope", "범위", "memory_id", "기억 번호", "revision", "버전"),
        CONNECTOR_ATTACH,
        Map.of("connector", "서비스", "agent", "만들 에이전트", "tools", "쓸 수 있게 되는 도구", "caution", "주의", "connector_id", "서비스 번호",
            "agent_name", "에이전트 이름"));


    /** 그 동작의 결과를 사용자가 확인할 화면의 이름이다. 결과를 적지 못한 줄의 알림 줄이 쓴다. */
    public static String screen(String toolName) {
        return toolName == null ? "그 화면" : SCREENS.getOrDefault(toolName, "그 화면");
    }


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
