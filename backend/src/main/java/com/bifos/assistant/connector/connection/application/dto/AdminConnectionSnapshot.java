package com.bifos.assistant.connector.connection.application.dto;

import com.bifos.assistant.connector.connection.domain.ConnectorBinding;
import com.bifos.assistant.connector.connection.domain.type.BindingStatus;
import java.time.Instant;

/**
 * 관리자가 보는 바인딩 한 줄이다. 다른 사용자의 칸 값과 비밀 앞부분은 담지 않는다.
 *
 * @param restartRequiredSince 재시작이 필요해진 가장 늦은 설치 시각. 다시 확인 요청이 이 값을 그대로 돌려보낸다
 * @param applyFailed 자동 반영이 실패했다(스펙 DECISION10)
 * @param waitingSince 자동 대기가 시작된 시각. 대기가 없으면 null
 * @param installing 시작한 설치의 결과를 아직 적지 않았다. 다시 확인하면 진행 중인 설치를 덮는다
 */
public record AdminConnectionSnapshot(
    String connectorId,
    Long userId,
    String displayName,
    String agentCode,
    BindingStatus status,
    boolean restartRequired,
    Instant restartRequiredSince,
    int undeclaredTools,
    boolean applyFailed,
    Instant waitingSince,
    boolean installing) {

    public static AdminConnectionSnapshot from(ConnectorBinding binding, String displayName, Instant now) {
        return new AdminConnectionSnapshot(
            binding.connection().connectorId(),
            binding.connection().userId(),
            displayName,
            binding.agent().code(),
            binding.status(),
            binding.restartRequired(),
            binding.restartRequiredSince(),
            binding.connection().undeclaredTools(),
            binding.applyFailedAt() != null,
            binding.waitingSince(),
            binding.installInFlight(now));
    }


    /**
     * 관리자가 「다시 확인」 을 누를 줄인가(스펙 DECISION16). 자동 반영이 실패했거나 대기가 {@code staleBefore} 전에 시작된 {@code PENDING}
     * 줄이다. 대기 시각이 없는 {@code PENDING} 은 자동 경로가 맡지 않는 줄이라 관리자가 다시 확인할 수 있게 둔다. 설치 결과를 기다리는
     * 줄은 아니다.
     */
    public boolean recheckable(Instant staleBefore) {
        if (status != BindingStatus.PENDING || installing) {
            return false;
        }
        return applyFailed || waitingSince == null || !waitingSince.isAfter(staleBefore);
    }
}
