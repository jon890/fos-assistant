package com.bifos.assistant.connector.connection.application;

import com.bifos.assistant.agent.application.AgentLifecycleService;
import com.bifos.assistant.agent.application.PersonaService;
import com.bifos.assistant.agent.application.dto.PersonaSnapshot;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentIntroduction;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.connector.connection.application.dto.AgentConnectionView;
import com.bifos.assistant.connector.connection.application.dto.BoundAgentSummary;
import com.bifos.assistant.connector.connection.application.dto.SpecialistAgent;
import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.connection.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 연결 하나만 맡는 전문 에이전트를 한 요청으로 만든다(docs/features/agent-team/spec.md 의 REQ5, REQ6).
 *
 * <p>에이전트 만들기(소개 포함), 성격 틀 쓰기, 붙이기, 다른 에이전트에서 떼기를 차례로 부르고, 중간에 실패하면 역순으로 거둔다.
 * 순서와 까닭은 docs/features/agent-team/design.md 의 「PR3 마법사」 가 갖는다.
 *
 * <p>자기 트랜잭션을 두지 않는다. 부르는 단계가 저마다 잠그고 커밋하고, profile 만들기와 설치는 트랜잭션 밖의 외부 호출이다.
 * 하나로 묶으면 외부 호출 동안 잠금을 쥔다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpecialistAgentCreator {

    private final AgentLifecycleService lifecycle;
    private final PersonaService personas;
    private final ConnectorBindingService bindingService;
    private final ConnectorConnectionRepository connections;
    private final ConnectorBindingRepository bindings;
    private final HermesConnectorClient connector;
    private final TransactionTemplate transactions;


    /**
     * 만들고 붙인다. 아무것도 만들기 전에 거절할 것(목록 밖 플러그인, 준비 안 된 연결, 떼기 없는 {@code single_binding})을 먼저 본다.
     *
     * @param detach 그 연결이 지금 붙은 다른 에이전트에서 모두 뗀다
     */
    public SpecialistAgent create(CurrentUser user, String connectorId, AgentIntroduction introduction, boolean detach) {
        ConnectorManifest manifest = ConnectorManifests.find(connector, connectorId).orElseThrow(ConnectorErrors::notFound);
        List<BoundAgentSummary> others = boundAgents(user, connectorId);
        if (manifest.singleBinding() && !others.isEmpty() && !detach) {
            throw new ApiException(ErrorCode.CONNECTOR_SINGLE_BINDING, "this connection is attached to another agent");
        }
        Agent agent = lifecycle.create(user, introduction, AgentVisibility.PRIVATE);
        List<BoundAgentSummary> detached = new ArrayList<>();
        try {
            writePersona(user, agent.code(), introduction, manifest.title());
            if (detach && manifest.singleBinding()) {
                // 붙어 있으면 새 에이전트에 붙일 수 없으므로 먼저 뗀다.
                detachFrom(user, connectorId, others, detached);
            }
            AgentConnectionView view = bindingService.bind(user, agent.code(), connectorId);
            if (detach && !manifest.singleBinding()) {
                // 붙인 뒤에 떼야 붙이기가 실패해도 쓰던 에이전트가 그대로다.
                detachFrom(user, connectorId, others, detached);
            }
            return new SpecialistAgent(agent.code(), agent.name(), view, detached);
        } catch (RuntimeException failure) {
            withdraw(user, agent.code(), connectorId, detached);
            throw failure;
        }
    }


    /** 그 연결이 붙은 에이전트들이다. 연결이 준비되지 않았으면 거절한다. 붙이기와 같은 기준이다. */
    private List<BoundAgentSummary> boundAgents(CurrentUser user, String connectorId) {
        return transactions.execute(status -> {
            ConnectorConnection connection = connections
                .findByUserIdAndConnectorId(user.id(), connectorId)
                .filter(found -> found.status() == ConnectionStatus.READY && found.vaultStored())
                .orElseThrow(() -> new ApiException(ErrorCode.CONNECTOR_NOT_CONNECTED, "connect this connector first"));
            return bindings.findByConnectionId(connection.id()).stream().map(BoundAgentSummary::from).toList();
        });
    }


    /** 새 profile 의 지금 본문을 읽고 그 지문으로 성격 틀을 쓴다. 성격 화면의 쓰기와 같은 경로다. */
    private void writePersona(CurrentUser user, String code, AgentIntroduction introduction, String connectorTitle) {
        PersonaSnapshot current = personas.read(user, code);
        personas.write(user, code, personaTemplate(introduction, connectorTitle), current.bodyHash());
    }


    /**
     * 전문 에이전트의 성격 틀이다. 이름과 플러그인 이름, 소개만 넣는다. 사용자는 상세의 성격에서 고친다.
     *
     * <p>매 실행의 고정 프롬프트에 들어가므로 짧게 둔다.
     */
    static String personaTemplate(AgentIntroduction introduction, String connectorTitle) {
        StringBuilder body = new StringBuilder()
            .append("너는 「").append(introduction.name()).append("」이다. 「")
            .append(connectorTitle).append("」 일만 맡는 전문 에이전트다.\n");
        if (introduction.description() != null) {
            body.append(introduction.description()).append('\n');
        }
        return body.append("이 서비스의 도구와 함께 붙은 스킬로 일하고, 맡은 일이 이 서비스 밖이면 할 수 없다고 짧게 답한다.\n")
            .toString();
    }


    private void detachFrom(
        CurrentUser user, String connectorId, List<BoundAgentSummary> others, List<BoundAgentSummary> detached) {
        for (BoundAgentSummary other : others) {
            // 앞에서 읽은 뒤 사용자가 직접 뗐으면 뗀 것으로 적지 않는다. 적으면 실패했을 때 사용자가 뗀 에이전트에 다시 붙인다.
            if (!stillBound(user, other.agentCode(), connectorId)) {
                continue;
            }
            bindingService.unbind(user, other.agentCode(), connectorId);
            detached.add(other);
        }
    }


    private boolean stillBound(CurrentUser user, String agentCode, String connectorId) {
        return Boolean.TRUE.equals(transactions.execute(status -> bindings.findByConnectionId(
            connections.findByUserIdAndConnectorId(user.id(), connectorId).orElseThrow().id()).stream()
            .anyMatch(binding -> binding.agent().code().equals(agentCode))));
    }


    /**
     * 만든 것을 역순으로 거둔다. 새 에이전트를 지우면 그 바인딩과 profile, 성격 틀이 함께 거둬진다. 그다음 뗀 에이전트에 다시 붙인다.
     * {@code single_binding} 은 새 에이전트의 바인딩이 없어진 뒤에야 다시 붙는다.
     *
     * <p>거두기의 실패는 원래 오류를 가리지 않는다. 부르는 쪽이 원래 오류를 던지고, 여기서는 오류 로그만 남긴다.
     */
    private void withdraw(CurrentUser user, String code, String connectorId, List<BoundAgentSummary> detached) {
        try {
            lifecycle.delete(user, code);
        } catch (RuntimeException failure) {
            log.error("전문 에이전트를 만들다 실패해 지우려 했으나 그것도 실패했다. 새 에이전트가 남는다 agent={}", code, failure);
        }
        for (BoundAgentSummary other : detached) {
            try {
                bindingService.bind(user, other.agentCode(), connectorId);
            } catch (RuntimeException failure) {
                log.error(
                    "전문 에이전트를 만들다 실패해 뗀 연결을 다시 붙이려 했으나 실패했다 agent={} connector={}",
                    other.agentCode(),
                    connectorId,
                    failure);
            }
        }
    }
}
