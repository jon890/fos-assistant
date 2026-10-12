package com.bifos.assistant.connector.application;

import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.infra.ConnectorActionExecutionRepository;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.Objects;
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


    /** 호출자는 이 조회 전에 소유 사용자 행을 잠근다. 자기 승인 줄은 제외한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireAvailable(String requestKey, Long ownActionId) {
        for (var row : contents.findByRequestKeyOrderByActionIdAsc(requestKey)) {
            if (!Objects.equals(row.actionId(), ownActionId)) {
                var action = actions.findById(row.actionId()).orElseThrow(ConnectorFinancialConflicts::rejected);
                if (BLOCKING.contains(action.status())) {
                    throw rejected();
                }
            }
        }
    }


    private static ApiException rejected() {
        return new ApiException(ErrorCode.CONNECTOR_ACTION_NOT_PENDING, "financial intent unavailable");
    }
}
