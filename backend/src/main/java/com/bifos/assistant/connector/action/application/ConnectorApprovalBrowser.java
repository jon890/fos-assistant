package com.bifos.assistant.connector.action.application;

import com.bifos.assistant.browser.application.BrowserHumanHold;
import com.bifos.assistant.browser.application.UserBrowserService;
import com.bifos.assistant.connector.action.domain.ConnectorAction;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.connection.application.ConnectorCatalogCache;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 「내 브라우저」 를 쓰는 플러그인의 승인 줄을 승인하기 전에 그 사용자의 브라우저를 켠다. 켜지 못하면 줄을 건드리지 않고
 * {@code CONNECTOR_BROWSER_UNAVAILABLE} 로 거절해 사람이 다시 승인할 수 있게 한다. 뜻은
 * {@code docs/features/browser-cowork/spec.md} 의 REQ2 와 설계의 「PR1 승인할 때 브라우저를 켜지 못하면」 이 갖는다.
 *
 * <p>승인 트랜잭션보다 먼저 부른다. 켜기는 {@code start-timeout} 까지 걸려 그동안 DB 연결과 사용자 행 잠금을 쥐지 않게 한다.
 * 브라우저를 쓰지 않는 상한 변경 줄은 부르는 쪽이 먼저 가른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorApprovalBrowser {

    /** 켜지 못한 까닭 가운데 잠시 뒤 다시 승인하면 풀릴 수 있는 것이다. 기능이 꺼진 {@code BROWSER_DISABLED} 는 그대로 둔다. */
    private static final Set<ErrorCode> UNAVAILABLE = Set.of(
        ErrorCode.BROWSER_CAPACITY,
        ErrorCode.BROWSER_START_FAILED,
        ErrorCode.BROWSER_BUSY,
        ErrorCode.BROWSER_STOP_FAILED,
        ErrorCode.BROWSER_NOT_FOUND);

    private final ConnectorActionRepository actions;
    private final ConnectorCatalogCache catalog;
    private final UserBrowserService browsers;
    private final BrowserHumanHold holds;
    private final Clock clock;


    /**
     * 그 줄이 요청자의 기다리는 브라우저 플러그인 승인이면 브라우저를 켜고 활동을 기록한다.
     *
     * <p>줄이 없거나 남의 것이거나 기다림이 아니거나 시간이 지났으면 아무것도 하지 않는다. 뒤의 승인 판정이 지금 응답을 그대로 낸다.
     * 남의 줄로 브라우저를 켜지 않는다.
     *
     * @throws ApiException 사람이 그 브라우저를 쥐고 있으면 {@code CONNECTOR_BROWSER_HELD}(spec DECISION26), 켜지 못했으면
     * {@code CONNECTOR_BROWSER_UNAVAILABLE}, 기능이 꺼졌으면 {@code BROWSER_DISABLED}
     */
    public void require(CurrentUser user, UUID actionId) {
        Optional<ConnectorAction> waiting = actions.findByPublicId(actionId)
            .filter(action -> Objects.equals(action.userId(), user.id()))
            .filter(action -> action.status() == ActionStatus.PENDING)
            .filter(action -> action.expiresAt() != null && action.expiresAt().isAfter(clock.instant()));
        if (waiting.isEmpty() || !usesBrowser(waiting.get().connectorId())) {
            return;
        }
        // 돈이 걸린 승인이 사람의 조작과 엉키지 않게 승인을 쓰지 않는다. 줄은 기다림 그대로라 돌려준 뒤 다시 승인한다
        if (holds.held(user.id())) {
            throw new ApiException(
                ErrorCode.CONNECTOR_BROWSER_HELD, "the user browser is held by the person for this approval");
        }
        try {
            browsers.ensureRunning(user.id());
        } catch (ApiException ex) {
            if (!UNAVAILABLE.contains(ex.code())) {
                throw ex;
            }
            log.warn("browser unavailable for approval actionId={} code={}", actionId, ex.code());
            throw unavailable(ex);
        } catch (RuntimeException ex) {
            log.warn("browser unavailable for approval actionId={} kind={}", actionId, ex.getClass().getSimpleName());
            throw unavailable(ex);
        }
        browsers.touch(user.id());
    }


    /**
     * 그 플러그인이 「내 브라우저」 를 쓰는가. 읽지 못하면 켜지 않고 지나간다. 그때는 이 판정이 없던 때처럼 실행이 중계에 닿을 때
     * 브라우저가 켜진다.
     */
    private boolean usesBrowser(String connectorId) {
        try {
            return catalog.find(connectorId).map(manifest -> manifest.ownerBrowser()).orElse(false);
        } catch (RuntimeException ex) {
            log.warn("connector {} catalog read failed: {}", connectorId, ex.getClass().getSimpleName());
            return false;
        }
    }


    private static ApiException unavailable(RuntimeException cause) {
        return new ApiException(
            ErrorCode.CONNECTOR_BROWSER_UNAVAILABLE, "the user browser could not be started for this approval", cause);
    }
}
