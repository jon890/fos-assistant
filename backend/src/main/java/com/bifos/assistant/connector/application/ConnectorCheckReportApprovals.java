package com.bifos.assistant.connector.application;

import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.proactive.application.CheckReportApprovalSource;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 모델이 적은 승인 번호를 쓰지 않고 그 실행 트리가 만든 실제 승인 카드에서 보고 칸을 채운다. */
@Component
@RequiredArgsConstructor
public class ConnectorCheckReportApprovals implements CheckReportApprovalSource {

    private final ConnectorActionRepository actions;

    @Override
    @Transactional(readOnly = true)
    public List<UUID> pendingPublicIds(Long rootExecutionId) {
        if (rootExecutionId == null) {
            return List.of();
        }
        return actions.findPublicIdsForTree(rootExecutionId, ActionStatus.PENDING);
    }
}
