package com.bifos.assistant.connector.action.application.dto;

import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import java.util.UUID;

/**
 * Control Plane 승인 카드를 띄운 결과다.
 *
 * @param created 이번 호출이 새 카드를 만들었는가. 같은 요청의 카드가 이미 있으면 거짓이다
 * @param status 그 카드의 지금 상태
 */
public record ControlPlaneApprovalRequest(UUID actionId, boolean created, ActionStatus status) {
}
