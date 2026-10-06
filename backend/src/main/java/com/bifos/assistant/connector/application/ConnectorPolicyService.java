package com.bifos.assistant.connector.application;

import com.bifos.assistant.chat.application.ConversationNotices;
import com.bifos.assistant.connector.application.model.ConnectorActionChanged;
import com.bifos.assistant.connector.application.model.ConnectorActionView;
import com.bifos.assistant.connector.application.model.ConnectorPolicyAnswer;
import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.ConnectorActionKey;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.HermesToolName;
import com.bifos.assistant.connector.domain.ToolPolicy;
import com.bifos.assistant.connector.domain.ToolPolicyDecision;
import com.bifos.assistant.connector.domain.ToolPolicyDecision.CheckBoundary;
import com.bifos.assistant.connector.domain.type.ActionDecision;
import com.bifos.assistant.connector.domain.type.ActionDenyReason;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.domain.type.BindingStatus;
import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.infra.ConnectorToolGrantRepository;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.notification.application.NotificationService;
import com.bifos.assistant.notification.domain.NotificationTarget;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.domain.type.NotificationTargetType;
import com.bifos.assistant.orchestration.application.SessionOwnerResolver;
import com.bifos.assistant.proactive.application.CheckNotificationPolicy;
import com.bifos.assistant.proactive.application.ProactiveCheckGuard;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.util.Sha256;
import com.bifos.assistant.usage.domain.AgentExecution;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 커넥터 도구 호출 하나를 판정하고 {@code connector_action} 에 한 줄을 남긴다(ADR-049).
 *
 * <p>순서는 {@code docs/backend/connector-tool-policy.md} 의 「도구 호출 판정」 이 갖는다. 줄을 남기지 않는 호출은 두 가지다. 실행이나 연결을
 * 찾지 못한 호출은 줄에 적을 사용자와 에이전트를 알 수 없어서이고, 같은 키로 다른 도구나 다른 인자를 보낸 호출은 키가
 * 유니크라 새 줄을 만들 수 없어서다.
 *
 * <p>연결은 그 실행의 에이전트에 붙은 바인딩에서 고른다(ADR-083). 한 에이전트에 연결이 여럿이므로 등록 이름의 서버 앞부분이
 * 맞는 바인딩 하나를 쓴다.
 *
 * <p>통과로 답하는 것은 판정이 허용일 때뿐이다. 승인이 필요한 호출은 막고 인자 원문과 함께 {@code PENDING} 으로
 * 저장한다(ADR-050). 실행은 주인이 승인한 뒤 {@link ConnectorActionService} 가 한다. 그 밖의 줄은 인자 원문을 저장하지 않고
 * 해시만 남긴다.
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
    /** 연결은 쓸 수 있는데 이 에이전트에 붙인 것이 아직 반영되지 않았다. 연결 확인을 다시 해도 풀리지 않는다. */
    static final String BINDING_PENDING_MESSAGE = "관리자가 반영을 마치면 이 연결을 쓸 수 있다. 지금은 실행하지 않았으니 사용자에게 반영을 기다리라고 알린다.";

    private static final String UNDECLARED_MESSAGE = "이 도구는 사용이 허락되지 않아 실행하지 않았다. 다시 부르지 않는다.";
    private static final String RISK_NOT_OPEN_MESSAGE = "이 도구는 아직 열리지 않아 실행하지 않았다. 다시 부르지 않는다.";
    private static final String ARGS_TOO_LARGE_MESSAGE = "인자가 너무 커서 실행하지 않았다. 나눠서 요청한다.";
    private static final String READ_ONLY_RUN_MESSAGE = "먼저 살펴보기에서는 읽기 도구만 쓸 수 있습니다.";
    /**
     * 승인을 기다리는 호출에 답하는 글이다. 모델이 읽는다.
     *
     * <p>약속은 실제 동작과 같아야 한다. 대화 화면에 승인 카드가 뜨고, 승인하면 저장한 인자로 한 번 실행한 결과가 그
     * 대화의 다음 turn 으로 온다({@link ConnectorActionResultSource}). 번호는 줄을 가리키는 값이라 사용자에게 읽어 줄
     * 것이 아니다.
     */
    private static final String APPROVAL_MESSAGE = "이 동작은 사용자의 승인이 필요하다. 승인 요청 번호는 %s 다. "
            + "대화 화면에 승인 카드가 떴으니 사용자에게 거기서 승인해 달라고 알린다. 번호는 사용자에게 말하지 않는다. "
            + "같은 도구를 다시 부르지 않는다. 승인하면 저장한 인자 그대로 한 번 실행되고 결과가 이 대화로 온다.";

    /** 대화 없이 돈 실행은 승인 카드가 뜰 화면이 없다. 승인받을 수 있다고 약속하지 않는다. */
    private static final String APPROVAL_WITHOUT_CONVERSATION_MESSAGE =
            "이 동작은 사용자의 승인이 필요하다. 승인 요청 번호는 %s 다. " + "이 실행은 대화가 없어 승인받을 화면이 없으므로 실행되지 않는다. 같은 도구를 다시 부르지 않는다.";

    /** 새 승인 줄을 알리는 알림의 제목이다. 본문은 도구 제목이다. */
    static final String APPROVAL_REQUESTED_TITLE = "승인을 기다리는 요청이 있어요";

    private final ConnectorActionRepository actions;
    private final ConnectorBindingRepository bindings;
    private final ConnectorToolGrantRepository grants;
    private final SessionOwnerResolver owners;
    private final ConnectorCatalogCache catalog;
    private final ConnectorPolicyProperties properties;
    private final ApplicationEventPublisher events;
    private final NotificationService notifications;
    private final ConversationNotices conversations;
    private final ProactiveCheckGuard checkGuard;
    private final CheckNotificationPolicy checkNotifications;
    private final TransactionTemplate transactions;
    private final Clock clock;

    // 생성자를 직접 쓴다. TransactionTemplate 은 transaction manager 로 여기서 만들고,
    // 검사가 시각을 고정할 수 있게 Clock 을 받는 생성자를 따로 둔다.
    @Autowired
    public ConnectorPolicyService(
            ConnectorActionRepository actions,
            ConnectorBindingRepository bindings,
            ConnectorToolGrantRepository grants,
            SessionOwnerResolver owners,
            ConnectorCatalogCache catalog,
            ConnectorPolicyProperties properties,
            ApplicationEventPublisher events,
            NotificationService notifications,
            ConversationNotices conversations,
            ProactiveCheckGuard checkGuard,
            CheckNotificationPolicy checkNotifications,
            PlatformTransactionManager transactionManager) {
        this(
                actions,
                bindings,
                grants,
                owners,
                catalog,
                properties,
                events,
                notifications,
                conversations,
                checkGuard,
                checkNotifications,
                transactionManager,
                Clock.systemUTC());
    }

    public ConnectorPolicyService(
            ConnectorActionRepository actions,
            ConnectorBindingRepository bindings,
            ConnectorToolGrantRepository grants,
            SessionOwnerResolver owners,
            ConnectorCatalogCache catalog,
            ConnectorPolicyProperties properties,
            ApplicationEventPublisher events,
            NotificationService notifications,
            ConversationNotices conversations,
            ProactiveCheckGuard checkGuard,
            CheckNotificationPolicy checkNotifications,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.actions = actions;
        this.bindings = bindings;
        this.grants = grants;
        this.owners = owners;
        this.catalog = catalog;
        this.properties = properties;
        this.events = events;
        this.notifications = notifications;
        this.conversations = conversations;
        this.checkGuard = checkGuard;
        this.checkNotifications = checkNotifications;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @param profileName 토큰이 증명한 profile
     * @param rootSessionId 서명을 확인한 루트 session
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
        List<ConnectorBinding> attached =
                origin.agentId() == null ? List.of() : bindings.findByAgentId(origin.agentId());
        // 등록 이름의 서버 앞부분으로 그 호출의 연결을 고른다. 대시보드가 한 profile 에서 서버 이름이 겹치지 않게 막으므로
        // 맞는 바인딩은 하나다. 둘 이상이면 어느 연결의 정책으로 판정할지 정할 수 없어 막는다.
        ConnectorBinding binding = null;
        Optional<ConnectorManifest> manifest = Optional.empty();
        int matches = 0;
        for (ConnectorBinding candidate : attached) {
            Optional<ConnectorManifest> declared =
                    readManifest(candidate.connection().connectorId());
            if (servesTool(candidate, declared, hermesTool)) {
                binding = candidate;
                manifest = declared;
                matches++;
            }
        }
        if (matches == 0) {
            return blockedWithoutRecord("그 실행의 에이전트에 그 도구의 서버를 붙인 연결이 없다");
        }
        if (matches > 1) {
            return blockedWithoutRecord("등록 이름에 맞는 연결이 둘 이상이다");
        }
        ConnectorConnection connection = binding.connection();
        if (!connection.userId().equals(origin.userId())) {
            return blockedWithoutRecord("연결의 주인이 실행의 사용자와 다르다");
        }
        if (!binding.agent().hermesProfile().equals(profileName)) {
            return blockedWithoutRecord("바인딩의 에이전트 profile 이 토큰의 profile 과 다르다");
        }
        ConnectionStatus usable = usableStatus(connection, binding);
        boolean bindingPending =
                connection.status() == ConnectionStatus.READY && binding.status() != BindingStatus.READY;

        Instant now = Instant.now(clock);
        // 등록 이름과 맞는 것을 확인한 원래 이름만 쓴다. manifest 가 없으면 확인할 수 없어 비운다.
        String confirmedTool = manifest.map(value -> confirmedTool(value, hermesTool, toolName))
                .orElse(null);
        // 승인 카드와 알림이 같은 도구 제목을 쓰도록 선언을 한 번 찾아 둔다.
        Optional<ToolPolicy> declared = manifest.flatMap(value -> ConnectorToolPolicies.find(value, confirmedTool));
        // 직접 부른 호출도 위임 자식의 호출도 같은 트리 루트로 경계가 정해진다. 살펴보기 turn 의 트리면 루트가 그 turn 이다
        // (ADR-080). 그 살펴보기가 쓰기 도구를 허용했으면 쓰기를 거절하지 않고 승인 줄로 보낸다(ADR-082).
        CheckBoundary boundary = checkBoundary(origin);
        ToolPolicyDecision decision = manifest.map(value -> ToolPolicyDecision.decide(
                        usable,
                        ownServerTool(value, hermesTool),
                        value.schema(),
                        declared,
                        granted(connection, confirmedTool, now),
                        argsJson.getBytes(StandardCharsets.UTF_8).length,
                        boundary))
                .orElseGet(ToolPolicyDecision::policyUnavailable);
        // 허용만 통과시킨다. 승인이 필요한 호출을 통과시키면 사람의 확인 없이 쓰기가 나간다.
        boolean passed = decision.decision() == ActionDecision.ALLOWED;
        boolean needsApproval = decision.decision() == ActionDecision.NEEDS_APPROVAL;
        if (needsApproval) {
            // 같은 실행이 같은 도구를 같은 인자로 다시 불렀다. 새 줄을 만들지 않고 앞선 요청의 번호로 답한다.
            Optional<ConnectorAction> waiting =
                    actions.findFirstByOriginExecutionIdAndHermesToolAndArgsSha256AndStatusOrderByIdAsc(
                            origin.id(), hermesTool, argsSha256, ActionStatus.PENDING);
            if (waiting.isPresent()) {
                return answer(waiting.get());
            }
        }
        ConnectorAction action = ConnectorAction.decided(
                connection,
                origin.agentId(),
                origin,
                hermesTool,
                confirmedTool,
                decision,
                passed,
                dedupeKey,
                argsSha256,
                now);
        if (needsApproval) {
            action.awaitApproval(argsJson, now.plus(properties.approvalTtl()));
        }
        try {
            ConnectorAction saved = transactions.execute(status -> {
                ConnectorAction stored = actions.saveAndFlush(action);
                // 알림은 승인 줄과 한 트랜잭션이다. 알림 저장이 실패하면 승인 줄도 남지 않는다(ADR-070).
                if (needsApproval && stored.conversationId() != null && checkNotifications.allows(origin, now)) {
                    notifyApprovalRequested(stored, ConnectorActionView.titleOf(declared));
                }
                return stored;
            });
            // 사건은 커밋한 뒤에 낸다. 받은 쪽이 읽었을 때 줄이 있어야 한다.
            if (needsApproval && saved.conversationId() != null) {
                events.publishEvent(new ConnectorActionChanged(saved.conversationId(), saved.publicId()));
            }
            if (bindingPending && saved.denyReason() == ActionDenyReason.NOT_READY) {
                return new ConnectorPolicyAnswer(false, BINDING_PENDING_MESSAGE, null);
            }
            return answer(saved);
        } catch (DataIntegrityViolationException ex) {
            // 같은 호출이 동시에 와 유니크 제약에 걸렸다. 먼저 저장된 줄의 판정을 그대로 돌려준다.
            return actions.findByDedupeKey(dedupeKey)
                    .map(first -> replayed(first, hermesTool, argsSha256))
                    .orElseThrow(() -> ex);
        }
    }

    /** 살펴보기 줄을 한 번 읽어 경계를 정한다. 줄이 없으면 살펴보기 트리가 아니다. */
    private CheckBoundary checkBoundary(AgentExecution origin) {
        return checkGuard
                .checkOf(origin)
                .map(check -> check.writesAllowed() ? CheckBoundary.APPROVAL_ONLY : CheckBoundary.READ_ONLY)
                .orElse(CheckBoundary.NOT_CHECK);
    }

    /** 승인 줄의 주인에게 새 요청을 알린다. 대화가 지워졌거나 없으면 눌러도 갈 곳이 없어 남기지 않는다. */
    private void notifyApprovalRequested(ConnectorAction action, String toolTitle) {
        conversations
                .publicIdOf(action.conversationId())
                .ifPresent(conversationId -> notifications.notify(
                        action.userId(),
                        NotificationKind.APPROVAL_REQUESTED,
                        APPROVAL_REQUESTED_TITLE,
                        "「" + toolTitle + "」",
                        new NotificationTarget(NotificationTargetType.CONVERSATION, conversationId)));
    }

    /** 그 사용자가 그 커넥터의 그 도구에 준 유효한 상시 허락이 있는가. 원래 이름을 확인하지 못한 호출은 없는 것이다. */
    private boolean granted(ConnectorConnection connection, String confirmedTool, Instant now) {
        return confirmedTool != null
                && grants.existsActive(connection.userId(), connection.connectorId(), confirmedTool, now);
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
     * <p>허용한 줄은 그 에이전트에 그 연결이 지금도 붙어 있고 연결과 바인딩이 모두 {@code READY} 일 때만 다시 허용한다.
     * 연결을 해제하거나 떼어 낸 뒤에 같은 호출이 다시 와도 앞의 허용이 나가지 않게 한다. 막은 줄과 승인 줄은 연결 상태와
     * 상관없이 처음 답을 돌려준다.
     */
    private ConnectorPolicyAnswer replayed(ConnectorAction recorded, String hermesTool, String argsSha256) {
        if (!recorded.hermesTool().equals(hermesTool)) {
            return blockedWithoutRecord("같은 키의 줄과 등록 이름이 다르다");
        }
        if (!recorded.argsSha256().equals(argsSha256)) {
            return blockedWithoutRecord("같은 키의 줄과 인자가 다르다");
        }
        if (recorded.passed() && !stillReady(recorded.agentId(), recorded.connectorId())) {
            log.warn("connector policy replay blocked: 허용한 줄의 연결이 지금은 READY 가 아니다");
            return new ConnectorPolicyAnswer(false, NOT_READY_MESSAGE, null);
        }
        return answer(recorded);
    }

    private boolean stillReady(Long agentId, String connectorId) {
        return bindings.findByAgentId(agentId).stream()
                .filter(binding -> binding.connection().connectorId().equals(connectorId))
                .anyMatch(binding -> usableStatus(binding.connection(), binding) == ConnectionStatus.READY);
    }

    /**
     * 판정에 넘길 연결 상태다. 연결이 {@code READY} 이고 그 에이전트에 붙인 바인딩도 {@code READY} 일 때만 {@code READY} 다.
     *
     * <p>값이 확인됐어도 공유 gateway 가 아직 그 profile 의 MCP 서버를 보지 못했으면 쓸 수 없다. 승인한 호출의 실행도 같은
     * 규칙으로 다시 판정한다.
     */
    static ConnectionStatus usableStatus(ConnectorConnection connection, ConnectorBinding binding) {
        return connection.status() == ConnectionStatus.READY && binding.status() == BindingStatus.READY
                ? ConnectionStatus.READY
                : ConnectionStatus.PENDING;
    }

    /**
     * 등록 이름이 그 바인딩의 MCP 서버가 낸 도구의 것인가.
     *
     * <p>manifest 를 읽었으면 그 서버 이름으로 본다. 읽지 못했으면 붙일 때 적어 둔 서버 이름으로 고른다. 그 호출은 정책을 확인하지
     * 못한 줄로 남는다.
     */
    private static boolean servesTool(
            ConnectorBinding binding, Optional<ConnectorManifest> manifest, String hermesTool) {
        if (manifest.isPresent()) {
            return ownServerTool(manifest.get(), hermesTool);
        }
        return binding.mcpServer() != null && hermesTool.startsWith(HermesToolName.of(binding.mcpServer(), ""));
    }

    /** 등록 이름이 그 커넥터의 MCP 서버가 낸 도구의 것인가. 서버 이름까지의 앞부분이 같은지로 본다. */
    static boolean ownServerTool(ConnectorManifest manifest, String hermesTool) {
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

    /** 승인 줄은 지금 상태와 상관없이 승인을 기다리라는 글과 그 번호로 답한다. 같은 호출이 다시 와도 답이 같다. */
    private static ConnectorPolicyAnswer answer(ConnectorAction action) {
        if (action.status() != null) {
            String message = action.conversationId() == null ? APPROVAL_WITHOUT_CONVERSATION_MESSAGE : APPROVAL_MESSAGE;
            return new ConnectorPolicyAnswer(false, String.format(message, action.publicId()), action.publicId());
        }
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
            case READ_ONLY_RUN -> READ_ONLY_RUN_MESSAGE;
        };
    }
}
