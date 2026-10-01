package com.bifos.assistant.connector.presentation;

import com.bifos.assistant.connector.application.model.AdminConnectionSnapshot;
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

/** 커넥터 연결 경로의 요청과 응답 모양이다. 칸은 {@code docs/connectors.md} 의 「Control Plane API」 와 같다. */
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

    /** 위험도와 승인 방식은 enum 이름 그대로 낸다. */
    public record ConnectorToolView(String name, String title, String risk, String approval) {
        static ConnectorToolView from(ConnectorToolSummary value) {
            return new ConnectorToolView(
                    value.name(),
                    value.title(),
                    value.risk().name(),
                    value.approval().name());
        }
    }

    public record ConnectorView(
            String id,
            String title,
            String description,
            List<ConnectorFieldView> fields,
            List<ConnectorToolView> tools,
            String myStatus,
            boolean available) {
        static ConnectorView from(ConnectorSummary value) {
            return new ConnectorView(
                    value.id(),
                    value.title(),
                    value.description(),
                    value.fields().stream().map(ConnectorFieldView::from).toList(),
                    value.tools().stream().map(ConnectorToolView::from).toList(),
                    value.myStatus().name(),
                    value.available());
        }
    }

    public record OptionView(String value, String label) {
        static OptionView from(ConnectorOption value) {
            return new OptionView(value.value(), value.label());
        }
    }

    public record ConnectionView(
            String connectorId,
            String status,
            Map<String, String> secretPrefixes,
            Map<String, String> values,
            Instant checkedAt,
            String agentCode,
            boolean restartRequired,
            int undeclaredTools) {
        static ConnectionView from(ConnectionSnapshot value) {
            return new ConnectionView(
                    value.connectorId(),
                    value.status().name(),
                    value.secretPrefixes(),
                    value.values(),
                    value.checkedAt(),
                    value.agentCode(),
                    value.restartRequired(),
                    value.undeclaredTools());
        }
    }

    /**
     * 도구 호출 판정의 응답이다. 칸은 {@code docs/connectors.md} 의 「도구 호출 판정」 과 같다.
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

    /** 다른 사용자의 칸 값과 비밀 앞부분은 담지 않는다. */
    public record AdminConnectionView(
            String connectorId,
            Long userId,
            String displayName,
            String status,
            String agentCode,
            boolean restartRequired,
            int undeclaredTools) {
        static AdminConnectionView from(AdminConnectionSnapshot value) {
            return new AdminConnectionView(
                    value.connectorId(),
                    value.userId(),
                    value.displayName(),
                    value.status().name(),
                    value.agentCode(),
                    value.restartRequired(),
                    value.undeclaredTools());
        }
    }
}
