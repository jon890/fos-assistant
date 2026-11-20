package com.bifos.assistant.connector.action.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.application.AgentToolService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.connector.action.domain.ControlPlaneActions;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.connection.application.ConnectorConnectionService;
import com.bifos.assistant.connector.connection.application.dto.BoundAgentSummary;
import com.bifos.assistant.connector.connection.application.dto.ConnectorSummary;
import com.bifos.assistant.connector.connection.application.dto.ConnectorToolSummary;
import com.bifos.assistant.connector.connection.domain.type.BindingStatus;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.connection.domain.type.ToolRisk;
import com.bifos.assistant.mcp.application.port.ConnectorAttachRequests;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 「이 에이전트에 붙이기」 승인 카드를 띄운다(스펙 service-concepts REQ15, REQ16). 붙일 수 없으면 카드 없이 까닭과 안내할 화면을 돌려준다.
 *
 * <p>붙일 수 있는지는 승인하는 때에 화면의 붙이기 검사가 다시 본다. 여기의 검사는 헛된 카드를 띄우지 않으려는 것이다. 결과에
 * 플러그인의 이름을 넣지 않는다. 차례와 값은 {@code docs/features/service-concepts/design.md} 의 「PR4 연결 붙이기 승인 카드」 가 갖는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ConnectorAttachRequester implements ConnectorAttachRequests {
    /** 화면의 붙이기 확인 창이 늘 보이는 안내다. */
    static final String CAUTION = "이 에이전트가 이 서비스의 도구를 직접 써요. 명령 실행이나 파일 도구를 함께 쓰면 연결 정보에 접근할 수 있어요. "
        + "읽은 내용이 다른 도구를 통해 인터넷으로 나갈 수도 있어요.";
    /** 셸, 파일, 코드 실행 도구가 켜졌거나 도구를 읽지 못한 에이전트에 덧붙이는 경고다. 화면의 확인 창과 같다. */
    static final String SHELL_CAUTION = " 지금 이 에이전트에는 명령 실행이나 파일 도구가 켜져 있어요. 안전하게 분리되지 않은 에이전트는 연결 정보를 읽고 "
        + "승인 없이 서비스를 이용할 수 있어요. 분리된 에이전트도 읽은 내용을 인터넷으로 보낼 수 있어요.";
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
    private final AgentToolService agentTools;
    private final ControlPlaneApprovals approvals;


    @Override
    public Map<String, Object> request(CurrentUser user, Long originExecutionId, Long agentId, String connectorId) {
        Optional<ConnectorSummary> found = connections.catalog(user).stream()
            .filter(ConnectorSummary::available)
            .filter(summary -> summary.id().equals(connectorId))
            .findFirst();
        if (found.isEmpty()) {
            return result("not_found", "그런 서비스가 없다. 카드를 띄우지 않았다. connector_list 로 id 를 다시 확인하거나 link 의 화면을 안내한다.", CONNECTIONS);
        }
        ConnectorSummary summary = found.get();
        String screen = CONNECTIONS + "/" + summary.id();
        Optional<Agent> agent = agents.findById(agentId)
            .filter(candidate -> !candidate.isDeleted())
            .filter(candidate -> Objects.equals(candidate.ownerUserId(), user.id()));
        if (agent.isEmpty()) {
            return result("not_owner",
                "이 에이전트는 사용자의 에이전트가 아니라서 붙일 수 없다. 카드를 띄우지 않았다. 사용자가 만든 비공개 에이전트와의 대화에서 부탁하거나 "
                    + "link 의 화면에서 붙일 에이전트를 고르라고 안내한다.",
                screen);
        }
        if (agent.get().visibility() != AgentVisibility.PRIVATE) {
            return result("agent_not_private",
                "그룹에 공개한 에이전트에는 붙일 수 없다. 카드를 띄우지 않았다. 비공개 에이전트에만 붙일 수 있다고 알리고 link 의 화면에서 "
                    + "붙일 에이전트를 고르라고 안내한다.",
                screen);
        }
        String code = agent.get().code();
        if (summary.myStatus() != ConnectionStatus.READY) {
            return result("not_connected",
                "이 서비스의 계정 연결이 아직 준비되지 않았다. 카드를 띄우지 않았다. link 의 화면에서 계정을 연결하라고 안내한다.",
                screen + "?agent=" + code);
        }
        Optional<BoundAgentSummary> bound = summary.bindings().stream()
            .filter(binding -> Objects.equals(binding.agentCode(), code))
            .findFirst();
        if (bound.isPresent()) {
            boolean ready = bound.get().status() == BindingStatus.READY && !bound.get().restartRequired();
            Map<String, Object> body = result(ALREADY_ATTACHED,
                ready ? "이미 이 에이전트에 붙어 있고 지금 쓸 수 있다. 카드를 띄우지 않았다."
                    : "이미 이 에이전트에 붙어 있고 반영을 기다리는 중이다. 카드를 띄우지 않았다. 조금 뒤에 쓸 수 있다고 알린다.",
                null);
            body.put("thisAgent", ready ? "ready" : "pending");
            return body;
        }
        Map<String, String> args = new LinkedHashMap<>();
        args.put("connector", summary.title());
        args.put("agent", agent.get().name());
        args.put("tools", toolSummary(summary.tools()));
        args.put("caution", CAUTION + (shellOrFile(user, agent.get()) ? SHELL_CAUTION : ""));
        args.put("connector_id", summary.id());
        args.put("agent_code", code);
        var request = approvals.request(
            user, originExecutionId, ControlPlaneActions.CONNECTOR_ATTACH, summary.id() + ":" + agent.get().id(), args);
        if (request.status() != ActionStatus.PENDING) {
            return result("closed", "같은 요청의 승인 카드가 이미 끝났다(" + request.status().name()
                + "). 다시 붙이려면 사용자에게 확인한 뒤 사용자의 다음 요청에서 부른다.", null);
        }
        return result(REQUESTED, "「이 에이전트에 붙이기」 승인 카드를 띄웠다. 아직 붙이지 않았다. 사용자가 카드에서 승인해야 붙는다.", null);
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


    /** 그 에이전트에 셸이나 파일 도구가 켜졌는가. 읽지 못하면 화면처럼 켜졌다고 보고 경고한다. */
    private boolean shellOrFile(CurrentUser user, Agent agent) {
        try {
            return agentTools.read(user, agent).shellOrFileEnabled();
        } catch (RuntimeException ex) {
            log.info("agent tools unreadable for attach card agentId={} kind={}", agent.id(), ex.getClass().getSimpleName());
            return true;
        }
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
