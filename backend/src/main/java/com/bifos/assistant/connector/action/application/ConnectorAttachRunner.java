package com.bifos.assistant.connector.action.application;

import com.bifos.assistant.connector.action.application.dto.ControlPlaneActionOutcome;
import com.bifos.assistant.connector.action.application.port.ControlPlaneActionRunner;
import com.bifos.assistant.connector.action.domain.ControlPlaneActions;
import com.bifos.assistant.connector.connection.application.ConnectorBindingService;
import com.bifos.assistant.connector.connection.application.dto.AgentConnectionView;
import com.bifos.assistant.connector.connection.domain.type.BindingStatus;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 사용자가 승인한 「이 에이전트에 붙이기」 를 한다(스펙 service-concepts REQ15). 화면의 붙이기와 같은
 * {@link ConnectorBindingService#bind} 를 부르므로 주인, 비공개, 연결 준비, 이름 겹침을 승인하는 때에 다시 본다.
 *
 * <p>붙이기는 설치 호출을 보내고 자기 트랜잭션을 열어 승인 트랜잭션 밖에서 돈다. 알림 줄은 화면의 사용 시점 안내와 같은 나눔이다
 * (스펙 DECISION26).
 */
@Component
@RequiredArgsConstructor
class ConnectorAttachRunner implements ControlPlaneActionRunner {
    /** 바인딩 서비스가 승인 서비스를 쓰고 승인 서비스가 실행기를 쓴다. 생성 순환을 피하려고 부를 때 꺼낸다. */
    private final ObjectProvider<ConnectorBindingService> bindings;


    @Override
    public String toolName() {
        return ControlPlaneActions.CONNECTOR_ATTACH;
    }


    @Override
    public boolean outsideTransaction() {
        return true;
    }


    @Override
    public ControlPlaneActionOutcome run(CurrentUser user, Map<String, String> args) {
        String connectorId = args.getOrDefault("connector_id", "");
        String agentCode = args.getOrDefault("agent_code", "");
        String target = "「" + args.getOrDefault("connector", connectorId) + "」 를 「" + args.getOrDefault("agent", agentCode) + "」 에";
        if (connectorId.isBlank() || agentCode.isBlank()) {
            return ControlPlaneActionOutcome.failed(target + " 붙이지 못했어요. 요청 내용을 읽을 수 없어요");
        }
        AgentConnectionView view;
        try {
            view = bindings.getObject().bind(user, agentCode, connectorId);
        } catch (ApiException ex) {
            return ControlPlaneActionOutcome.failed(refusal(target, ex.code()));
        }
        if (!view.bound()) {
            return ControlPlaneActionOutcome.failed(target + " 붙이지 못했어요. 외부 서비스 연결 화면에서 상태를 확인해 주세요");
        }
        if (view.restartRequired()) {
            return ControlPlaneActionOutcome.done(target + " 붙였어요. 관리자가 반영을 확인하면 쓸 수 있어요");
        }
        return ControlPlaneActionOutcome.done(
            target + (view.status() == BindingStatus.READY ? " 붙였어요. 바로 쓸 수 있어요" : " 붙였어요. 몇 분 안에 쓸 수 있어요"));
    }


    /** 화면의 붙이기 오류와 같은 까닭이다(web 의 {@code connection.ts}). */
    private static String refusal(String target, ErrorCode code) {
        String reason = switch (code) {
            case CONNECTOR_OPERATION_FAILED -> null;
            case AGENT_CONNECTIONS_REQUIRE_PRIVATE -> "비공개 에이전트에만 붙일 수 있어요";
            case CONNECTOR_NOT_CONNECTED -> "외부 서비스 연결 화면에서 연결을 확인해 주세요";
            case CONNECTOR_SINGLE_BINDING -> "이 연결은 다른 에이전트에 붙어 있어요. 그 에이전트에서 뗀 뒤 붙여 주세요";
            case CONNECTOR_BIND_CONFLICT, SKILL_NAME_TAKEN -> "이 에이전트의 다른 연결이나 스킬과 이름이 겹쳐요";
            case CONNECTOR_PROFILE_NOT_READY -> "이 에이전트는 아직 연결을 받을 준비가 되지 않았어요. 관리자에게 알려 주세요";
            case AGENT_SANDBOX_UNAVAILABLE -> "격리된 실행 공간이 준비된 에이전트에만 붙일 수 있어요. 관리자에게 알려 주세요";
            case AGENT_NOT_FOUND, FORBIDDEN -> "에이전트가 없거나 이 계정에서 관리할 수 없어요";
            case AGENT_BUSY -> "다른 설정 변경이 끝난 뒤 다시 요청해 주세요";
            case CONNECTOR_NOT_FOUND -> "그 서비스를 지금은 쓸 수 없어요";
            default -> "외부 서비스 연결 화면에서 다시 시도해 주세요";
        };
        if (reason == null) {
            // 설치 호출이 실패했다. 바인딩이 PENDING 으로 남았을 수 있고 다음 연결 확인이 설치를 다시 보낸다.
            return target + " 붙이는 중이에요. 반영을 확인하지 못했어요. 외부 서비스 연결 화면에서 상태를 확인해 주세요";
        }
        return target + " 붙이지 못했어요. " + reason;
    }
}
