package com.bifos.assistant.connector.presentation;

import com.bifos.assistant.connector.application.model.AdminConnectionSnapshot;
import com.bifos.assistant.connector.application.model.AgentConnectionView;
import com.bifos.assistant.connector.application.model.AgentConnectionsView;
import com.bifos.assistant.connector.application.model.BoundAgentSummary;
import com.bifos.assistant.connector.application.model.ConnectionSnapshot;
import com.bifos.assistant.connector.application.model.ConnectorFieldSummary;
import com.bifos.assistant.connector.application.model.ConnectorOption;
import com.bifos.assistant.connector.application.model.ConnectorPolicyAnswer;
import com.bifos.assistant.connector.application.model.ConnectorSummary;
import com.bifos.assistant.connector.application.model.ConnectorToolSummary;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 커넥터 연결 경로의 요청과 응답 모양이다. 칸은 {@code backend/docs/flow.md} 의 「커넥터 연결 API」 와 같다. */
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
    public record ConnectorToolView(String name, String title, String risk, String approval, boolean grant) {
        static ConnectorToolView from(ConnectorToolSummary value) {
            return new ConnectorToolView(
                    value.name(),
                    value.title(),
                    value.risk().name(),
                    value.approval().name(),
                    value.grant());
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
            String ownerBrowserLoginUrl) {
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
                    value.ownerBrowserLoginUrl());
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
        static ConnectorPolicyResponse from(ConnectorPolicyAnswer value) {
            return new ConnectorPolicyResponse(
                    value.allowed() ? "allow" : "block",
                    value.message(),
                    value.actionId() == null ? null : value.actionId().toString());
        }
    }

    /**
     * 관리자가 보는 바인딩 한 줄이다. 다른 사용자의 칸 값과 비밀 앞부분은 담지 않는다.
     *
     * @param restartRequiredSince 반영 완료 요청이 그대로 돌려보낼 재시작 대기 시작 시각
     */
    public record AdminConnectionView(
            String connectorId,
            Long userId,
            String displayName,
            String agentCode,
            String status,
            boolean restartRequired,
            Instant restartRequiredSince,
            int undeclaredTools) {
        static AdminConnectionView from(AdminConnectionSnapshot value) {
            return new AdminConnectionView(
                    value.connectorId(),
                    value.userId(),
                    value.displayName(),
                    value.agentCode(),
                    value.status().name(),
                    value.restartRequired(),
                    value.restartRequiredSince(),
                    value.undeclaredTools());
        }
    }

    /**
     * 관리자 반영 완료 요청이다.
     *
     * @param restartRequiredSince 관리자 목록에서 본 그 바인딩의 재시작 대기 시작 시각. 대기가 없었으면 null
     */
    public record ConfirmRequest(Instant restartRequiredSince) {}

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
     * @param blockedReason 붙일 수 없는 까닭. 붙일 수 있으면 null 이다. {@code AGENT_NOT_PRIVATE} 나 {@code LEGACY_AGENT}
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
