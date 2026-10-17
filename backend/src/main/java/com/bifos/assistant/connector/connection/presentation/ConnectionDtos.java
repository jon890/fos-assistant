package com.bifos.assistant.connector.connection.presentation;

import com.bifos.assistant.connector.connection.application.dto.AgentConnectionView;
import com.bifos.assistant.connector.connection.application.dto.AgentConnectionsView;
import com.bifos.assistant.connector.connection.application.dto.BoundAgentSummary;
import com.bifos.assistant.connector.connection.application.dto.ConnectionSnapshot;
import com.bifos.assistant.connector.connection.application.dto.ConnectorFieldSummary;
import com.bifos.assistant.connector.connection.application.dto.ConnectorOption;
import com.bifos.assistant.connector.connection.application.dto.ConnectorPolicyAnswer;
import com.bifos.assistant.connector.connection.application.dto.ConnectorSummary;
import com.bifos.assistant.connector.connection.application.dto.ConnectorToolSummary;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 커넥터 연결 경로의 요청과 응답 모양이다. 칸은 {@code docs/features/connector.md} 의 「커넥터 연결 API」 와 같다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ConnectionDtos {

    /** 칸 값을 담은 요청이다. 값에 비밀 원문이 있으므로 문자열 표현에 싣지 않는다. */
    public record ValuesRequest(Map<String, String> values) {
        @Override
        public String toString() {
            return "ValuesRequest[values=[REDACTED]]";
        }
    }

    public record ConnectorFieldView(
        String key,
        String label,
        String description,
        boolean secret,
        boolean required,
        String pattern,
        boolean hasOptions,
        boolean autoSelectSingle) {
        static ConnectorFieldView from(ConnectorFieldSummary value) {
            return new ConnectorFieldView(
                value.key(),
                value.label(),
                value.description(),
                value.secret(),
                value.required(),
                value.pattern(),
                value.hasOptions(),
                value.autoSelectSingle());
        }
    }

    /**
     * 위험도와 승인 방식은 enum 이름 그대로 낸다.
     *
     * @param grant 그 도구에 상시 허락을 줄 수 있는가
     */
    public record ConnectorToolView(String name, String title, String risk, String approval, boolean grant, String summary) {
        /** 요약이 없는 옛 응답과 호환하는 생성자다. */
        public ConnectorToolView(String name, String title, String risk, String approval, boolean grant) {
            this(name, title, risk, approval, grant, null);
        }


        static ConnectorToolView from(ConnectorToolSummary value) {
            return new ConnectorToolView(
                value.name(),
                value.title(),
                value.risk().name(),
                value.approval().name(),
                value.grant(),
                value.summary());
        }
    }

    /**
     * 연결이 붙은 에이전트 하나다. 상태는 enum 이름 그대로 낸다.
     *
     * @param restartRequired 그 에이전트의 profile 이 공유 gateway 재시작을 기다린다
     */
    public record BoundAgentView(String agentCode, String agentName, String status, boolean restartRequired) {
        static BoundAgentView from(BoundAgentSummary value) {
            return new BoundAgentView(
                value.agentCode(), value.agentName(), value.status().name(), value.restartRequired());
        }
    }

    /**
     * 카탈로그의 커넥터 하나와 내 연결 상태다.
     *
     * @param icon 검증을 통과한 아이콘의 data URL. 없거나 틀렸으면 null
     * @param link 검증을 통과한 {@code https://} 링크. 없거나 틀렸으면 null
     * @param ownerBrowserLoginUrl 사용자 브라우저에서 먼저 로그인할 {@code https://} 주소. 선언하지 않았으면 null
     */
    public record ConnectorView(
        String id,
        String title,
        String description,
        String icon,
        String link,
        List<ConnectorFieldView> fields,
        List<ConnectorToolView> tools,
        String myStatus,
        boolean available,
        List<BoundAgentView> bindings,
        String ownerBrowserLoginUrl,
        String readmeUrl,
        String linkKind) {
        static ConnectorView from(ConnectorSummary value) {
            return new ConnectorView(
                value.id(),
                value.title(),
                value.description(),
                value.appearance().icon(),
                value.appearance().link(),
                value.fields().stream().map(ConnectorFieldView::from).toList(),
                value.tools().stream().map(ConnectorToolView::from).toList(),
                value.myStatus().name(),
                value.available(),
                value.bindings().stream().map(BoundAgentView::from).toList(),
                value.ownerBrowserLoginUrl(),
                value.appearance().readmeUrl(),
                value.appearance().linkKind());
        }
    }

    public record OptionView(String value, String label) {
        static OptionView from(ConnectorOption value) {
            return new OptionView(value.value(), value.label());
        }
    }

    /** 재시작 대기는 연결이 아니라 붙은 에이전트마다 {@code bindings} 에 있다. */
    public record ConnectionView(
        String connectorId,
        String status,
        Map<String, String> secretPrefixes,
        Map<String, String> values,
        Instant checkedAt,
        List<BoundAgentView> bindings,
        int undeclaredTools) {
        static ConnectionView from(ConnectionSnapshot value) {
            return new ConnectionView(
                value.connectorId(),
                value.status().name(),
                value.secretPrefixes(),
                value.values(),
                value.checkedAt(),
                value.bindings().stream().map(BoundAgentView::from).toList(),
                value.undeclaredTools());
        }
    }

    /**
     * 도구 호출 판정의 응답이다.
     *
     * @param decision {@code allow} 나 {@code block}
     * @param message {@code block} 일 때 모델에게 보일 글. {@code allow} 이면 빈 글이다
     * @param actionId 승인 요청 번호. 승인 요청을 만들지 않았으면 null
     */
    public record ConnectorPolicyResponse(
        String decision,
        String message,
        @JsonProperty("action_id") String actionId) {
        public static ConnectorPolicyResponse from(ConnectorPolicyAnswer value) {
            return new ConnectorPolicyResponse(
                value.allowed() ? "allow" : "block",
                value.message(),
                value.actionId() == null ? null : value.actionId().toString());
        }
    }

    /**
     * 에이전트 하나에서 본 연결 하나다. 상태는 enum 이름 그대로 내고, 붙어 있지 않으면 {@code status} 가 null 이다.
     *
     * @param toolCount 커넥터가 선언한 도구 수
     * @param skills 붙이면 그 profile 에 설치되는 커넥터 스킬 이름
     */
    public record AgentConnectionResponse(
        String connectorId,
        String title,
        String connectionStatus,
        boolean bound,
        String status,
        boolean restartRequired,
        int toolCount,
        List<String> skills) {
        static AgentConnectionResponse from(AgentConnectionView value) {
            return new AgentConnectionResponse(
                value.connectorId(),
                value.title(),
                value.connectionStatus().name(),
                value.bound(),
                value.status() == null ? null : value.status().name(),
                value.restartRequired(),
                value.toolCount(),
                value.skills());
        }
    }

    /**
     * 에이전트 하나의 연결 목록이다. 주인에게만 낸다. env 이름, 보관 파일 이름, 서버 이름은 담지 않는다.
     *
     * @param blockedReason 붙일 수 없는 까닭. 붙일 수 있으면 null 이다. {@code AGENT_NOT_PRIVATE}
     */
    public record AgentConnectionsResponse(List<AgentConnectionResponse> connections, String blockedReason) {
        static AgentConnectionsResponse from(AgentConnectionsView value) {
            return new AgentConnectionsResponse(
                value.connections().stream()
                    .map(AgentConnectionResponse::from)
                    .toList(),
                value.blockedReason());
        }
    }
}
