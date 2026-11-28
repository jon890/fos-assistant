package com.bifos.assistant.connector.action.application;

import com.bifos.assistant.connector.action.application.dto.ControlPlaneActionOutcome;
import com.bifos.assistant.connector.action.application.port.ControlPlaneActionRunner;
import com.bifos.assistant.connector.action.domain.ControlPlaneActions;
import com.bifos.assistant.agent.domain.AgentIntroduction;
import com.bifos.assistant.connector.connection.application.SpecialistAgentCreator;
import com.bifos.assistant.connector.connection.application.dto.SpecialistAgent;
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
 * 사용자가 승인한 「새 전문 에이전트를 만들어 붙이기」 를 한다(비서 중심 대화 스펙 REQ9, DECISION25). 연결 뒤 「전문 에이전트 만들기」 와 같은
 * {@link SpecialistAgentCreator#create} 를 불러, 연결 준비와 {@code single_binding} 을 승인하는 때에 다시 보고 실패하면 만든 것을 거둔다.
 *
 * <p>만들기와 붙이기는 설치 호출을 보내고 자기 트랜잭션을 열어 승인 트랜잭션 밖에서 돈다. 알림 줄은 화면의 사용 시점 안내와 같은 나눔이다
 * (스펙 service-concepts DECISION26). 배포 전에 뜬 옛 카드(인자에 {@code agent_name} 이 없다)는 지금 에이전트에 붙이던 카드라
 * 승인해도 붙이지 않는다. 연결은 전문 에이전트에만 붙이기 때문이다.
 */
@Component
@RequiredArgsConstructor
class ConnectorAttachRunner implements ControlPlaneActionRunner {
    /** 새 전문 에이전트의 아이콘이다. 웹의 {@code specialistDraft} 와 같다. */
    static final String ICON = "🤖";
    /** 바인딩 서비스가 승인 서비스를 쓰고 승인 서비스가 실행기를 쓴다. 생성 순환을 피하려고 부를 때 꺼낸다. */
    private final ObjectProvider<SpecialistAgentCreator> creators;


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
        String agentName = args.getOrDefault("agent_name", "");
        String connector = args.getOrDefault("connector", connectorId);
        if (connectorId.isBlank() || agentName.isBlank()) {
            return ControlPlaneActionOutcome.failed("「" + connector + "」 를 붙이지 못했어요. 예전 요청이라 다시 부탁해 주세요");
        }
        String target = "「" + agentName + "」 를 만들어 「" + connector + "」 를";
        SpecialistAgent created;
        try {
            created = creators.getObject().create(user, connectorId, introduction(agentName, connector), false);
        } catch (ApiException ex) {
            return ControlPlaneActionOutcome.failed(refusal(target, ex.code()));
        }
        AgentConnectionView view = created.connection();
        if (!view.bound()) {
            return ControlPlaneActionOutcome.failed(target + " 붙이지 못했어요. 외부 서비스 연결 화면에서 상태를 확인해 주세요");
        }
        if (view.restartRequired()) {
            return ControlPlaneActionOutcome.done(target + " 붙였어요. 관리자가 반영을 확인하면 쓸 수 있어요");
        }
        return ControlPlaneActionOutcome.done(
            target + (view.status() == BindingStatus.READY ? " 붙였어요. 바로 쓸 수 있어요" : " 붙였어요. 몇 분 안에 쓸 수 있어요"));
    }


    /** 연결 뒤 「전문 에이전트 만들기」 의 기본값과 같은 소개다. 소개 상한을 넘으면 서비스 이름을 줄인다. */
    static AgentIntroduction introduction(String agentName, String connector) {
        String description = connector + " 일을 맡아요";
        if (description.length() > AgentIntroduction.MAX_DESCRIPTION_CHARS) {
            description = null;
        }
        return AgentIntroduction.of(agentName, description, ICON, null);
    }


    /** 화면의 붙이기 오류와 같은 까닭이다(web 의 {@code connection.ts}). */
    private static String refusal(String target, ErrorCode code) {
        String reason = switch (code) {
            case CONNECTOR_OPERATION_FAILED -> null;
            case AGENT_CONNECTIONS_REQUIRE_PRIVATE -> "비공개 에이전트에만 붙일 수 있어요";
            case CONNECTOR_NOT_CONNECTED -> "외부 서비스 연결 화면에서 연결을 확인해 주세요";
            case CONNECTOR_SINGLE_BINDING -> "이 연결은 다른 에이전트에 붙어 있어요. 외부 서비스 연결 화면에서 확인해 주세요";
            case CONNECTOR_BIND_CONFLICT, SKILL_NAME_TAKEN -> "이 에이전트의 다른 연결이나 스킬과 이름이 겹쳐요";
            case CONNECTOR_PROFILE_NOT_READY -> "이 에이전트는 아직 연결을 받을 준비가 되지 않았어요. 관리자에게 알려 주세요";
            case AGENT_SANDBOX_UNAVAILABLE -> "격리된 실행 공간이 준비된 에이전트에만 붙일 수 있어요. 관리자에게 알려 주세요";
            case AGENT_NOT_FOUND, FORBIDDEN -> "에이전트가 없거나 이 계정에서 관리할 수 없어요";
            case AGENT_BUSY -> "다른 설정 변경이 끝난 뒤 다시 요청해 주세요";
            case CONNECTOR_NOT_FOUND -> "그 서비스를 지금은 쓸 수 없어요";
            case AGENT_LIMIT_REACHED -> "에이전트를 더 만들 수 없어요. 쓰지 않는 에이전트를 지운 뒤 다시 부탁해 주세요";
            default -> "외부 서비스 연결 화면에서 다시 시도해 주세요";
        };
        if (reason == null) {
            // 설치 호출이 실패했다. 바인딩이 PENDING 으로 남았을 수 있고 다음 연결 확인이 설치를 다시 보낸다.
            return target + " 붙이는 중이에요. 반영을 확인하지 못했어요. 외부 서비스 연결 화면에서 상태를 확인해 주세요";
        }
        return target + " 붙이지 못했어요. " + reason;
    }
}
