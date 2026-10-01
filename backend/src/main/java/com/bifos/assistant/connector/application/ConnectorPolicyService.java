package com.bifos.assistant.connector.application;

import com.bifos.assistant.connector.application.model.ConnectorPolicyAnswer;
import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.ConnectorActionKey;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.HermesToolName;
import com.bifos.assistant.connector.domain.ToolPolicyDecision;
import com.bifos.assistant.connector.domain.type.ActionDecision;
import com.bifos.assistant.connector.domain.type.ActionDenyReason;
import com.bifos.assistant.connector.domain.type.ConnectionStatus;
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
 * 커넥터 도구 호출 하나를 판정하고 {@code connector_action} 에 한 줄을 남긴다(ADR-049).
 *
 * <p>순서는 {@code docs/connectors.md} 의 「도구 호출 판정」 이 갖는다. 실행이나 연결을 찾지 못한 호출과 같은 키로 다른 도구나 다른
 * 인자를 보낸 호출은 줄을 남기지 않는다. 줄에 적을 사용자와 에이전트를 알 수 없기 때문이다.
 *
 * <p>통과로 답하는 것은 판정이 허용일 때뿐이다. 승인이 필요한 호출은 막고 {@code NEEDS_APPROVAL} 줄을 남긴다. 승인해 실행하는 길은
 * 아직 없으므로 그 호출은 실행되지 않는다(ADR-050). 승인 엔진은 이 분기에 승인 줄 저장을 잇는다. 인자 원문은 저장하지 않고 해시만 남긴다.
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
    private static final String NEEDS_APPROVAL_MESSAGE =
            "이 작업은 사용자 승인이 필요해 아직 실행하지 않았다. 같은 호출을 다시 시도하지 말고 사용자에게 승인이 필요하다고 알린다.";

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
        String argsSha256 = Sha256.hex(argsJson);
        Optional<ConnectorAction> recorded = actions.findByDedupeKey(dedupeKey);
        if (recorded.isPresent()) {
            return replayed(recorded.get(), hermesTool, argsSha256);
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
                        ownServerTool(value, hermesTool),
                        value.schema(),
                        ConnectorToolPolicies.find(value, confirmedTool),
                        false,
                        argsJson.getBytes(StandardCharsets.UTF_8).length))
                .orElseGet(ToolPolicyDecision::policyUnavailable);
        // 허용만 통과시킨다. 승인이 필요한 호출을 통과시키면 사람의 확인 없이 쓰기가 나간다.
        boolean passed = decision.decision() == ActionDecision.ALLOWED;
        ConnectorAction action = ConnectorAction.decided(
                connection,
                origin,
                hermesTool,
                confirmedTool,
                decision,
                passed,
                dedupeKey,
                argsSha256,
                Instant.now(clock));
        try {
            return answer(transactions.execute(status -> actions.saveAndFlush(action)));
        } catch (DataIntegrityViolationException ex) {
            // 같은 호출이 동시에 와 유니크 제약에 걸렸다. 먼저 저장된 줄의 판정을 그대로 돌려준다.
            return actions.findByDedupeKey(dedupeKey)
                    .map(first -> replayed(first, hermesTool, argsSha256))
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

    /**
     * 같은 키로 이미 남긴 줄의 판정을 돌려준다. 그 줄의 등록 이름이나 인자 해시가 이번 요청과 다르면 돌려주지 않고 막는다.
     *
     * <p>한 session 에서 같은 {@code tool_call_id} 가 되풀이되면 키가 같다. 줄의 판정을 그대로 주면 앞서 허용한
     * 읽기 도구의 답이 다른 도구나 다른 인자의 호출에 나간다. 새 줄은 만들지 않는다. 키가 유니크라 만들 수 없다.
     *
     * <p>허용한 줄은 연결이 지금도 {@code READY} 일 때만 다시 허용한다. 연결을 해제한 뒤에 같은 호출이 다시 와도
     * 앞의 허용이 나가지 않게 한다. 막은 줄은 연결 상태와 상관없이 처음 답을 돌려준다.
     */
    private ConnectorPolicyAnswer replayed(ConnectorAction recorded, String hermesTool, String argsSha256) {
        if (!recorded.hermesTool().equals(hermesTool)) {
            return blockedWithoutRecord("같은 키의 줄과 등록 이름이 다르다");
        }
        if (!recorded.argsSha256().equals(argsSha256)) {
            return blockedWithoutRecord("같은 키의 줄과 인자가 다르다");
        }
        if (recorded.passed() && !stillReady(recorded.agentId())) {
            log.warn("connector policy replay blocked: 허용한 줄의 연결이 지금은 READY 가 아니다");
            return new ConnectorPolicyAnswer(false, NOT_READY_MESSAGE, null);
        }
        return answer(recorded);
    }

    private boolean stillReady(Long agentId) {
        return connections
                .findByAgentId(agentId)
                .map(connection -> connection.status() == ConnectionStatus.READY)
                .orElse(false);
    }

    /** 등록 이름이 그 커넥터의 MCP 서버가 낸 도구의 것인가. 서버 이름까지의 앞부분이 같은지로 본다. */
    private static boolean ownServerTool(ConnectorManifest manifest, String hermesTool) {
        return hermesTool.startsWith(HermesToolName.of(manifest.mcpServer(), ""));
    }

    /**
     * hook 이 보낸 원래 이름으로 등록 이름을 다시 계산해 hook 이 받은 등록 이름과 다르면 이름이 없는 호출로 읽는다. 다른
     * 서버의 등록 이름이면 다시 계산한 값과 같을 수 없어 이름이 없는 호출이 된다.
     */
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
        if (action.decision() == ActionDecision.NEEDS_APPROVAL) {
            return new ConnectorPolicyAnswer(false, NEEDS_APPROVAL_MESSAGE, null);
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
