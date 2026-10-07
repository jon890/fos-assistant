package com.bifos.assistant.connector.application;

import com.bifos.assistant.connector.application.model.ConnectorActionResult;
import com.bifos.assistant.connector.application.model.ConnectorActionView;
import com.bifos.assistant.connector.application.model.PendingApproval;
import com.bifos.assistant.connector.domain.ActionDelivery;
import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.ToolPolicy;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.domain.type.ToolApproval;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.hermes.ToolDetailRedactor;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/** 승인 줄의 조회와 결과 전달 표시, 지금 카탈로그로 만든 표시 값을 맡는다. */
@Slf4j
class ConnectorActionDetails {
    private static final String NOT_FOUND_MESSAGE = "this connector action does not exist";

    /** 실행을 보낸 뒤 끝난 상태들이다. 자동 turn 으로 모델에게 전한다. */
    private static final Set<ActionStatus> EXECUTED =
            Set.of(ActionStatus.SUCCEEDED, ActionStatus.FAILED, ActionStatus.UNKNOWN);

    /** 실행하지 않고 끝난 상태들이다. 대화에 알림 줄만 남긴다. */
    private static final Set<ActionStatus> CLOSED_WITHOUT_EXECUTION =
            Set.of(ActionStatus.REJECTED, ActionStatus.EXPIRED);

    /** 도구를 선언하지 않는 manifest 판이다. */
    private static final int SCHEMA_WITHOUT_TOOLS = 1;

    private final ConnectorActionRepository actions;
    private final ConnectorCatalogCache catalog;

    ConnectorActionDetails(ConnectorActionRepository actions, ConnectorCatalogCache catalog) {
        this.actions = actions;
        this.catalog = catalog;
    }

    /**
     * 그 대화의 승인 줄이다. 답을 기다리는 줄 전부와 끝난 줄 가운데 최근 20개를 만든 순으로 준다.
     *
     * @param conversationId 주인인 것을 이미 확인한 대화의 번호
     */
    List<ConnectorActionView> listForConversation(CurrentUser user, Long conversationId) {
        List<ConnectorAction> found = new ArrayList<>(actions.findByConversationIdAndUserIdAndStatusOrderByIdAsc(
                conversationId, user.id(), ActionStatus.PENDING));
        found.addAll(actions.findTop20ByConversationIdAndUserIdAndStatusNotOrderByIdDesc(
                conversationId, user.id(), ActionStatus.PENDING));
        found.sort(Comparator.comparing(ConnectorAction::id));
        Map<String, Optional<ConnectorManifest>> manifests = new HashMap<>();
        return found.stream()
                .map(action -> view(action, manifests.computeIfAbsent(action.connectorId(), this::readManifest)))
                .toList();
    }

    /**
     * 대화마다 승인 줄의 결과를 전한 가장 늦은 시각이다. 전한 줄이 없는 대화는 빠진다. 먼저 알리기가 할 일에 연결한 대화의 결과
     * 도착을 볼 때 읽는다.
     *
     * <p>실행한 줄({@code SUCCEEDED}, {@code FAILED}, {@code UNKNOWN})만 센다. 거절하거나 만료한 줄도 알림 줄을 남기며 전한 시각을
     * 적지만, 승인한 동작의 결과가 아니다.
     *
     * <p>대화마다 읽지 않고 집계 한 번으로 읽는다. 번호가 비면 읽지 않는다.
     */
    Map<Long, Instant> lastResultDeliveredAt(Collection<Long> conversationIds) {
        if (conversationIds.isEmpty()) {
            return Map.of();
        }
        return actions.findLastDeliveredByConversation(conversationIds, EXECUTED).stream()
                .collect(Collectors.toMap(ActionDelivery::getConversationId, ActionDelivery::getDeliveredAt));
    }

    /**
     * 그 사용자의 답을 기다리는 승인 줄을 만든 순으로 준다. 먼저 알리기의 판정이 읽는다.
     *
     * <p>사람에게 보일 이름은 승인 카드와 같은 규칙이다. 커넥터마다 카탈로그를 한 번만 읽는다. 인자와 결과 글은 담지 않는다.
     */
    List<PendingApproval> pendingApprovalsOf(CurrentUser user) {
        Map<String, Optional<ConnectorManifest>> manifests = new HashMap<>();
        return actions.findByUserIdAndStatusOrderByIdAsc(user.id(), ActionStatus.PENDING).stream()
                .map(action -> new PendingApproval(
                        action.publicId(),
                        view(action, manifests.computeIfAbsent(action.connectorId(), this::readManifest))
                                .title(),
                        action.agentId(),
                        action.conversationId(),
                        action.createdAt(),
                        action.expiresAt()))
                .toList();
    }

    /** 그 대화에서 결과를 아직 전하지 않은 승인 줄이다. 실행을 보낸 뒤 끝난 것만이고 만든 순이다. */
    List<ConnectorActionResult> undeliveredResults(Long conversationId) {
        return undelivered(conversationId, EXECUTED);
    }

    /**
     * 그 대화와 그 사용자의 실행한 승인 줄을 번호의 순서로 다시 읽는다. 결과를 다시 전달할 때 쓴다(ADR-075).
     *
     * <p>전했는지는 보지 않는다. 없거나 남의 줄이거나 실행을 보내지 않고 끝난 줄은 뺀다. 실행을 다시 보내지 않는다.
     */
    List<ConnectorActionResult> resultsFor(Long conversationId, Long userId, List<UUID> actionIds) {
        if (actionIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, ConnectorAction> found = new HashMap<>();
        actions.findByConversationIdAndUserIdAndPublicIdIn(conversationId, userId, actionIds).stream()
                .filter(action -> EXECUTED.contains(action.status()))
                .forEach(action -> found.put(action.publicId(), action));
        Map<String, Optional<ConnectorManifest>> manifests = new HashMap<>();
        return actionIds.stream()
                .distinct()
                .map(found::get)
                .filter(Objects::nonNull)
                .map(action -> resultOf(action, manifests))
                .toList();
    }

    /** 그 대화에서 알림 줄만 남길 줄이다. 실행하지 않고 끝났고 아직 전하지 않은 것이다. */
    List<ConnectorActionResult> undeliveredClosures(Long conversationId) {
        return undelivered(conversationId, CLOSED_WITHOUT_EXECUTION);
    }

    /** 대화에 전했다고 적는다. 이미 전한 줄은 건드리지 않는다. 부르는 쪽의 트랜잭션에 함께 묶인다. */
    void markDelivered(List<UUID> actionIds, Instant now) {
        if (!actionIds.isEmpty()) {
            actions.markDelivered(actionIds, now);
        }
    }

    /**
     * 그 줄을 전하는 일을 이 호출이 맡았는가. 먼저 적은 쪽만 참을 받는다.
     *
     * <p>같은 줄의 사건이 겹쳐 와도 알림 줄을 한 번만 남기게 한다.
     */
    boolean claimDelivery(UUID actionId, Instant now) {
        return actions.markDelivered(List.of(actionId), now) == 1;
    }

    /** 실행한 결과를 아직 전하지 않은 대화들이다. 기동할 때 훑는다. */
    List<Long> conversationsWithUndelivered() {
        return actions.findConversationsWithUndelivered(EXECUTED);
    }

    private List<ConnectorActionResult> undelivered(Long conversationId, Set<ActionStatus> statuses) {
        Map<String, Optional<ConnectorManifest>> manifests = new HashMap<>();
        return actions
                .findByConversationIdAndStatusInAndResultDeliveredAtIsNullOrderByIdAsc(conversationId, statuses)
                .stream()
                .map(action -> resultOf(action, manifests))
                .toList();
    }

    /** @param manifests 커넥터마다 한 번만 읽으려고 부르는 쪽이 들고 있는 manifest 들 */
    private ConnectorActionResult resultOf(ConnectorAction action, Map<String, Optional<ConnectorManifest>> manifests) {
        ConnectorActionView view = view(action, manifests.computeIfAbsent(action.connectorId(), this::readManifest));
        return new ConnectorActionResult(
                action.publicId(),
                view.title(),
                action.toolName() == null ? action.hermesTool() : action.toolName(),
                action.status(),
                action.errorCode(),
                action.resultText(),
                action.userId(),
                action.executedAt());
    }

    /** 카탈로그에서 그 도구의 이름을 찾아 붙인다. 카탈로그를 읽지 못해도 줄은 돌려준다. */
    ConnectorActionView view(ConnectorAction action) {
        return view(action, readManifest(action.connectorId()));
    }

    /** @param manifest 그 줄의 커넥터 manifest. 읽지 못했으면 빈 값 */
    static ConnectorActionView view(ConnectorAction action, Optional<ConnectorManifest> manifest) {
        return ConnectorActionView.from(
                action,
                manifest.flatMap(found -> ConnectorToolPolicies.find(found, action.toolName())),
                grantable(manifest, action),
                hiddenArgs(manifest, action));
    }

    /**
     * 지금 카탈로그로 볼 때 그 줄의 도구에 상시 허락을 줄 수 있는가(ADR-065).
     *
     * <p>승인 줄에 저장하지 않고 읽을 때마다 본다. 선언이 바뀌면 바로 따른다. 카탈로그를 읽지 못했으면 줄 수 없는
     * 것으로 낸다. 도구를 선언하지 않는 판은 상시 허락을 닫는 선언을 둘 수 없으므로 선언 없는 도구에도 줄 수 있다.
     * 도구를 선언하는 판에서 선언이 없는 도구는 호출이 거절되므로 줄 수 없다.
     *
     * @param manifest 그 줄의 커넥터 manifest. 읽지 못했으면 빈 값
     */
    static boolean grantable(Optional<ConnectorManifest> manifest, ConnectorAction action) {
        if (manifest.isEmpty()) {
            return false;
        }
        return ConnectorToolPolicies.find(manifest.get(), action.toolName())
                .map(ToolPolicy::grantable)
                .orElse(manifest.get().schema() == SCHEMA_WITHOUT_TOOLS);
    }

    /**
     * 지금 카탈로그로 볼 때 그 줄의 도구가 상시 허락을 닫은 도구인가(ADR-065).
     *
     * <p>승인을 받는 도구이고 선언이 상시 허락을 닫았을 때다. 밖으로 나가는 호출이라 사람이 인자를 다 읽어야 한다.
     * 카탈로그를 읽지 못했으면 닫았는지 알 수 없으므로 거짓이다. 그 줄은 판정이 이미 {@code NOT_EXECUTABLE} 로
     * 끝내 실행되지 않는다. 선언 없는 도구와 상시 허락을 줄 수 있는 도구도 거짓이다.
     *
     * @param manifest 그 줄의 커넥터 manifest. 읽지 못했으면 빈 값
     */
    private static boolean grantClosed(Optional<ConnectorManifest> manifest, ConnectorAction action) {
        return manifest.flatMap(found -> ConnectorToolPolicies.find(found, action.toolName()))
                .filter(declared -> declared.approval() == ToolApproval.REQUIRED && !declared.grantable())
                .isPresent();
    }

    /**
     * 답을 기다리는 줄인데 사람이 인자를 다 읽을 수 없는가. 상시 허락을 닫은 도구의 인자에 화면에서 가려지는 글이
     * 있을 때다. 이런 줄은 승인할 수 없다. 커넥터가 식별자로 선언한 칸은 승인 줄의 가림과 같은 규칙으로 본다(ADR-089).
     *
     * <p>카탈로그를 읽지 못했으면 거짓이다. 그 도구가 상시 허락을 닫았는지 모르는 채 다른 커넥터의 줄에도 경고를 붙이면
     * 화면이 알리는 까닭이 틀린다. 실행은 {@link ConnectorActionApproval#beginApproval} 이 지금 정책을 판정하지 못한 줄로 막는다.
     */
    static boolean hiddenArgs(Optional<ConnectorManifest> manifest, ConnectorAction action) {
        return action.status() == ActionStatus.PENDING
                && grantClosed(manifest, action)
                && ToolDetailRedactor.hidesArguments(
                        action.argsJson(),
                        ConnectorActionView.identifiersOf(
                                manifest.flatMap(found -> ConnectorToolPolicies.find(found, action.toolName()))));
    }

    /** 읽지 못했거나 카탈로그에 없으면 빈 값이다. 예외 메시지에는 원격 응답이 섞일 수 있어 종류만 남긴다. */
    Optional<ConnectorManifest> readManifest(String connectorId) {
        try {
            return catalog.find(connectorId);
        } catch (RuntimeException ex) {
            log.warn(
                    "connector {} catalog read failed: {}",
                    connectorId,
                    ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    static ApiException notFound() {
        return new ApiException(ErrorCode.CONNECTOR_ACTION_NOT_FOUND, NOT_FOUND_MESSAGE);
    }
}
