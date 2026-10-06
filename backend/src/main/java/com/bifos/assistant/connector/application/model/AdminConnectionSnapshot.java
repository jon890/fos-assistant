package com.bifos.assistant.connector.application.model;

import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.type.BindingStatus;
import java.time.Instant;

/**
 * 관리자가 보는 바인딩 한 줄이다. 다른 사용자의 칸 값과 비밀 앞부분은 담지 않는다.
 *
 * @param restartRequiredSince 재시작이 필요해진 가장 늦은 설치 시각. 반영 완료 요청이 이 값을 그대로 돌려보낸다
 */
public record AdminConnectionSnapshot(
        String connectorId,
        Long userId,
        String displayName,
        String agentCode,
        BindingStatus status,
        boolean restartRequired,
        Instant restartRequiredSince,
        int undeclaredTools) {

    public static AdminConnectionSnapshot from(ConnectorBinding binding, String displayName) {
        return new AdminConnectionSnapshot(
                binding.connection().connectorId(),
                binding.connection().userId(),
                displayName,
                binding.agent().code(),
                binding.status(),
                binding.restartRequired(),
                binding.restartRequiredSince(),
                binding.connection().undeclaredTools());
    }
}
