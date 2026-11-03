package com.bifos.assistant.connector.financial.application;

import com.bifos.assistant.connector.action.domain.ConnectorAction;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.action.infra.ConnectorActionExecutionRepository;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 사용자 잠금 아래 준비·승인·claim이 같은 의도 차단 규칙을 사용한다. */
@Component
@RequiredArgsConstructor
public class ConnectorFinancialConflicts {
    private static final Set<ActionStatus> BLOCKING = Set.of(ActionStatus.PENDING, ActionStatus.EXECUTING, ActionStatus.UNKNOWN);
    private final ConnectorActionExecutionRepository contents;
    private final ConnectorActionRepository actions;
    private final Clock clock;


    /** 호출자는 이 조회 전에 소유 사용자 행을 잠근다. 자기 승인 줄은 제외한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireAvailable(String requestKey, Long ownActionId) {
        if (blocking(requestKey, ownActionId).isPresent()) {
            throw rejected();
        }
    }


    /**
     * 같은 의도를 막는 줄을 찾는다. 호출자는 이 조회 전에 소유 사용자 행을 잠근다. 자기 승인 줄은 제외한다.
     *
     * <p>승인 기한이 지난 {@code PENDING} 은 만료 작업이 아직 돌지 않았어도 막지 않는다. 그 줄은 승인할 때 기한 검사에 걸려
     * 실행되지 못하므로, 막아 봐야 사용자가 다시 요청할 길만 닫는다.
     *
     * @return 막는 줄. 없으면 빈 값
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ConnectorAction> blocking(String requestKey, Long ownActionId) {
        var now = clock.instant();
        for (var row : contents.findByRequestKeyOrderByActionIdAsc(requestKey)) {
            if (!Objects.equals(row.actionId(), ownActionId)) {
                var action = actions.findById(row.actionId()).orElseThrow(ConnectorFinancialConflicts::rejected);
                boolean expired = action.status() == ActionStatus.PENDING && action.expiresAt() != null && !action.expiresAt().isAfter(now);
                if (BLOCKING.contains(action.status()) && !expired) {
                    return Optional.of(action);
                }
            }
        }
        return Optional.empty();
    }


    private static ApiException rejected() {
        return new ApiException(ErrorCode.CONNECTOR_ACTION_NOT_PENDING, "financial intent unavailable");
    }
}
