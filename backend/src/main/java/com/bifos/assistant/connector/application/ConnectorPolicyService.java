package com.bifos.assistant.connector.application;

import com.bifos.assistant.connector.application.model.ConnectorPolicyAnswer;
import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.ConnectorActionKey;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.HermesToolName;
import com.bifos.assistant.connector.domain.ToolPolicyDecision;
import com.bifos.assistant.connector.domain.type.ActionDecision;
import com.bifos.assistant.connector.domain.type.ActionDenyReason;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.orchestration.application.SessionOwnerResolver;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.util.Sha256;
import com.bifos.assistant.usage.domain.AgentExecution;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 커넥터 도구 호출 하나를 판정하고 {@code connector_action} 에 한 줄을 남긴다(ADR-047).
 *
 * <p>순서는 {@code docs/connectors.md} 의 「도구 호출 판정」 이 갖는다. 실행이나 연결을 찾지 못한 호출은 줄을 남기지
 * 않는다. 줄에 적을 사용자와 에이전트를 알 수 없기 때문이다. 승인이 필요한 호출은 승인 엔진이 들어오기 전이라 통과로
 * 답하고 기록만 남긴다. 인자 원문은 저장하지 않고 해시만 남긴다.
 *
 * <p>{@code mcp} 패키지의 인증 주체를 모르게 하려고 profile 이름과 session 값을 문자열로 받는다.
 */
@Slf4j
@Service
public class ConnectorPolicyService {
    static final String CONTEXT_MESSAGE = "이 도구 호출의 실행 맥락을 확인하지 못해 실행하지 않았다.";

    private static final String POLICY_UNAVAILABLE_MESSAGE =
            "이 도구의 사용 정책을 지금 확인하지 못해 실행하지 않았다. 잠시 뒤 다시 시도하라고 사용자에게 알린다.";
    private static final String NOT_READY_MESSAGE = "이 연결이 준비되지 않아 실행하지 않았다. 사용자에게 연결 화면에서 연결을 확인하라고 알린다.";
    private static final String UNDECLARED_MESSAGE = "이 도구는 사용이 허락되지 않아 실행하지 않았다. 다시 부르지 않는다.";
    private static final String RISK_NOT_OPEN_MESSAGE = "이 도구는 아직 열리지 않아 실행하지 않았다. 다시 부르지 않는다.";
    private static final String ARGS_TOO_LARGE_MESSAGE = "인자가 너무 커서 실행하지 않았다. 나눠서 요청한다.";

    private final ConnectorActionRepository actions;
    private final ConnectorConnectionRepository connections;
    private final SessionOwnerResolver owners;
    private final ConnectorCatalogCache catalog;
    private final TransactionTemplate transactions;
    private final Clock clock;

    // 생성자를 직접 쓴다. TransactionTemplate 은 transaction manager 로 여기서 만들고,
    // 검사가 시각을 고정할 수 있게 Clock 을 받는 생성자를 따로 둔다.
    @Autowired
    public ConnectorPolicyService(
            ConnectorActionRepository actions,
            ConnectorConnectionRepository connections,
            SessionOwnerResolver owners,
            ConnectorCatalogCache catalog,
            PlatformTransactionManager transactionManager) {
        this(actions, connections, owners, catalog, transactionManager, Clock.systemUTC());
    }

    public ConnectorPolicyService(
            ConnectorActionRepository actions,
            ConnectorConnectionRepository connections,
            SessionOwnerResolver owners,
            ConnectorCatalogCache catalog,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.actions = actions;
        this.connections = connections;
        this.owners = owners;
        this.catalog = catalog;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @param profileName 토큰이 증명한 profile
     * @param rootSessionId 서명을 확인한 뿌리 session
     * @param sessionId 서명을 확인한, 도구를 부른 session
     * @param toolCallId 서명을 확인한 도구 호출 id
     * @param hermesTool 서명을 확인한 등록 이름
     * @param toolName hook 이 대응 파일에서 찾은 원래 도구 이름. 서명하지 않은 값이라 그대로 믿지 않는다. 없으면 null
     * @param argsJson 서명을 확인한 인자 글
     */
    public ConnectorPolicyAnswer decide(
            String profileName,
            String rootSessionId,
            String sessionId,
            String toolCallId,
            String hermesTool,
            String toolName,
            String argsJson) {
        String dedupeKey = ConnectorActionKey.of(profileName, rootSessionId, sessionId, toolCallId)
                .value();
        Optional<ConnectorAction> recorded = actions.findByDedupeKey(dedupeKey);
        if (recorded.isPresent()) {
            return answer(recorded.get());
        }
        final AgentExecution origin;
        try {
            origin = owners.resolve(profileName, rootSessionId, sessionId);
        } catch (ApiException ex) {
            // 까닭은 찾는 쪽이 이미 로그에 남겼다.
            return blockedWithoutRecord("origin 실행을 찾지 못했다");
        }
        Optional<ConnectorConnection> found =
                origin.agentId() == null ? Optional.empty() : connections.findByAgentId(origin.agentId());
        if (found.isEmpty()) {
            return blockedWithoutRecord("그 실행의 에이전트에 연결이 없다");
        }
        ConnectorConnection connection = found.get();
        if (!connection.userId().equals(origin.userId())) {
            return blockedWithoutRecord("연결의 주인이 실행의 사용자와 다르다");
        }
        if (!connection.agent().hermesProfile().equals(profileName)) {
            return blockedWithoutRecord("연결용 에이전트의 profile 이 토큰의 profile 과 다르다");
        }

        Optional<ConnectorManifest> manifest = readManifest(connection.connectorId());
        // 등록 이름과 맞는 것을 확인한 원래 이름만 쓴다. manifest 가 없으면 확인할 수 없어 비운다.
        String confirmedTool = manifest.map(value -> confirmedTool(value, hermesTool, toolName))
                .orElse(null);
        ToolPolicyDecision decision = manifest.map(value -> ToolPolicyDecision.decide(
                        connection.status(),
                        value.schema(),
                        ConnectorToolPolicies.find(value, confirmedTool),
                        false,
                        argsJson.getBytes(StandardCharsets.UTF_8).length))
                .orElseGet(ToolPolicyDecision::policyUnavailable);
        boolean passed = decision.decision() != ActionDecision.DENIED;
        ConnectorAction action = ConnectorAction.decided(
                connection,
                origin,
                hermesTool,
                confirmedTool,
                decision,
                passed,
                dedupeKey,
                Sha256.hex(argsJson),
                Instant.now(clock));
        try {
            return answer(transactions.execute(status -> actions.saveAndFlush(action)));
        } catch (DataIntegrityViolationException ex) {
            // 같은 호출이 동시에 와 유니크 제약에 걸렸다. 먼저 저장된 줄의 판정을 그대로 돌려준다.
            return actions.findByDedupeKey(dedupeKey)
                    .map(ConnectorPolicyService::answer)
                    .orElseThrow(() -> ex);
        }
    }

    /** 읽지 못했거나 카탈로그에 없으면 빈 값이다. 예외 종류만 남긴다. 예외 메시지에는 원격 응답이 섞일 수 있다. */
    private Optional<ConnectorManifest> readManifest(String connectorId) {
        try {
            return catalog.find(connectorId);
        } catch (RuntimeException ex) {
            log.warn(
                    "connector {} policy catalog read failed: {}",
                    connectorId,
                    ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** hook 이 보낸 원래 이름으로 등록 이름을 다시 계산해 hook 이 받은 등록 이름과 다르면 이름이 없는 호출로 읽는다. */
    private static String confirmedTool(ConnectorManifest manifest, String hermesTool, String toolName) {
        if (toolName == null
                || !HermesToolName.of(manifest.mcpServer(), toolName).equals(hermesTool)) {
            return null;
        }
        return toolName;
    }

    private static ConnectorPolicyAnswer blockedWithoutRecord(String reason) {
        log.warn("커넥터 도구 호출을 줄 없이 막았다 reason={}", reason);
        return new ConnectorPolicyAnswer(false, CONTEXT_MESSAGE, null);
    }

    private static ConnectorPolicyAnswer answer(ConnectorAction action) {
        if (action.passed()) {
            return new ConnectorPolicyAnswer(true, "", null);
        }
        return new ConnectorPolicyAnswer(false, message(action.denyReason()), null);
    }

    private static String message(ActionDenyReason reason) {
        if (reason == null) {
            return CONTEXT_MESSAGE;
        }
        return switch (reason) {
            case POLICY_UNAVAILABLE -> POLICY_UNAVAILABLE_MESSAGE;
            case NOT_READY -> NOT_READY_MESSAGE;
            case UNDECLARED -> UNDECLARED_MESSAGE;
            case RISK_NOT_OPEN -> RISK_NOT_OPEN_MESSAGE;
            case ARGS_TOO_LARGE -> ARGS_TOO_LARGE_MESSAGE;
        };
    }
}
