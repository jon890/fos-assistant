package com.bifos.assistant.connector.action.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.application.PrimaryAgents;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentIntroduction;
import com.bifos.assistant.connector.action.domain.ControlPlaneActions;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.connection.application.ConnectorConnectionService;
import com.bifos.assistant.connector.connection.application.dto.BoundAgentSummary;
import com.bifos.assistant.connector.connection.application.dto.ConnectorSummary;
import com.bifos.assistant.connector.connection.application.dto.ConnectorToolSummary;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.connection.domain.type.ToolRisk;
import com.bifos.assistant.mcp.application.port.ConnectorAttachRequests;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 「새 전문 에이전트를 만들어 붙이기」 승인 카드를 띄운다(비서 중심 대화 스펙 REQ9, DECISION25). 붙일 수 없으면 카드 없이 까닭과 안내할
 * 화면을 돌려준다.
 *
 * <p>연결은 전문 에이전트에만 붙인다. 그 연결이 붙은 전문 에이전트가 있으면 카드 없이 그 에이전트를 알려 모델이 맡기게 한다. 기본 비서에만
 * 붙어 있으면(배포 전부터 붙은 연결) 그대로 쓴다(DECISION7). 승인하면 {@link ConnectorAttachRunner} 가 만들고 붙인다. 결과에 플러그인의
 * 이름을 넣지 않는다. 차례와 값은 {@code docs/features/assistant-hub/design.md} 의 「PR3」 이 갖는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ConnectorAttachRequester implements ConnectorAttachRequests {
    /** 화면의 붙이기 확인 창이 늘 보이는 안내다. 새 에이전트는 셸과 파일 도구가 없어 그 경고는 붙이지 않는다. */
    static final String CAUTION = "새 에이전트가 이 서비스의 도구를 직접 써요. 읽은 내용이 다른 도구를 통해 인터넷으로 나갈 수도 있어요.";
    private static final String CONNECTIONS = "/connections";
    private static final Map<ToolRisk, String> RISK_LABELS = Map.of(
        ToolRisk.READ, "조회",
        ToolRisk.SENSITIVE, "민감한 조회",
        ToolRisk.WRITE, "쓰기",
        ToolRisk.DESTRUCTIVE, "되돌리기 어려운 쓰기",
        ToolRisk.FINANCIAL, "결제",
        ToolRisk.PURCHASE, "구매");

    private final ConnectorConnectionService connections;
    private final AgentService agents;
    private final PrimaryAgents primaryAgents;
    private final ControlPlaneApprovals approvals;


    @Override
    public Map<String, Object> request(CurrentUser user, Long originExecutionId, String connectorId) {
        Optional<ConnectorSummary> found = connections.catalog(user).stream()
            .filter(ConnectorSummary::available)
            .filter(summary -> summary.id().equals(connectorId))
            .findFirst();
        if (found.isEmpty()) {
            return result("not_found", "그런 서비스가 없다. 카드를 띄우지 않았다. connector_list 로 id 를 다시 확인하거나 link 의 화면을 안내한다.", CONNECTIONS);
        }
        ConnectorSummary summary = found.get();
        String screen = CONNECTIONS + "/" + summary.id();
        if (summary.myStatus() != ConnectionStatus.READY) {
            return result("not_connected",
                "이 서비스의 계정 연결이 아직 준비되지 않았다. 카드를 띄우지 않았다. link 의 화면에서 계정을 연결하라고 안내한다.", screen);
        }
        Optional<String> primary = primaryAgents.of(user).map(Agent::code);
        Set<String> bound = summary.bindings().stream().map(BoundAgentSummary::agentCode).collect(Collectors.toSet());
        // 요청자가 쓸 수 있고 켜진 에이전트만 본다. 맡기기(agent_list)와 같은 기준이다.
        Optional<Agent> specialist = agents.readableBy(user).stream()
            .filter(agent -> bound.contains(agent.code()))
            .filter(agent -> primary.filter(agent.code()::equals).isEmpty())
            .findFirst();
        if (specialist.isPresent()) {
            Map<String, Object> body = result(SPECIALIST_ATTACHED,
                "이 서비스는 이미 전문 에이전트에 붙어 있다. 카드를 띄우지 않았다. agent_delegate 로 agent_code 의 에이전트에게 맡긴다.", null);
            body.put("agent_code", specialist.get().code());
            body.put("agent_name", specialist.get().name());
            return body;
        }
        if (!summary.bindings().isEmpty()) {
            // 기본 비서에만 붙어 있다. 배포 전부터 붙은 연결은 그대로 쓴다(DECISION7).
            Map<String, Object> body = result(ALREADY_ATTACHED, "이미 이 에이전트에 붙어 있다. 카드를 띄우지 않았다.", null);
            body.put("thisAgent", "ready");
            return body;
        }
        String agentName = specialistName(summary.title());
        Map<String, String> args = new LinkedHashMap<>();
        args.put("connector", summary.title());
        args.put("agent", agentName);
        args.put("tools", toolSummary(summary.tools()));
        args.put("caution", CAUTION);
        args.put("connector_id", summary.id());
        args.put("agent_name", agentName);
        var request = approvals.request(
            user, originExecutionId, ControlPlaneActions.CONNECTOR_ATTACH, summary.id() + ":new", args);
        if (request.status() != ActionStatus.PENDING) {
            return result("closed", "같은 요청의 승인 카드가 이미 끝났다(" + request.status().name()
                + "). 다시 만들려면 사용자에게 확인한 뒤 사용자의 다음 요청에서 부른다.", null);
        }
        return result(REQUESTED, "「새 전문 에이전트를 만들어 붙이기」 승인 카드를 띄웠다. 아직 만들지 않았다. 사용자가 카드에서 승인해야 만들고 붙인다.", null);
    }


    /** 새 전문 에이전트의 이름이다. 연결 뒤 「전문 에이전트 만들기」 의 기본값(web 의 {@code specialistDraft})과 같다. */
    static String specialistName(String connectorTitle) {
        String name = connectorTitle + " 담당";
        return name.length() <= AgentIntroduction.MAX_NAME_CHARS ? name : name.substring(0, AgentIntroduction.MAX_NAME_CHARS);
    }


    /** 연결 카드와 같은 한 줄이다. 「도구 3개 · 조회 1 · 쓰기 2」. 위험도는 선언 순서로 늘어놓고 0개인 것은 뺀다. */
    static String toolSummary(List<ConnectorToolSummary> tools) {
        List<String> parts = new ArrayList<>();
        parts.add("도구 " + tools.size() + "개");
        for (ToolRisk risk : ToolRisk.values()) {
            long count = tools.stream().filter(tool -> tool.risk() == risk).count();
            if (count > 0) {
                parts.add(RISK_LABELS.get(risk) + " " + count);
            }
        }
        return String.join(" · ", parts);
    }


    private static Map<String, Object> result(String status, String message, String link) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("message", message);
        if (link != null) {
            body.put("link", link);
        }
        return body;
    }
}
