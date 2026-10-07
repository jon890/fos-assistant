package com.bifos.assistant.connector.application;

import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.HermesConnectorClient.ConnectorState;
import com.bifos.assistant.hermes.HermesConnectorClient.InstallResult;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 바인딩의 설치를 다시 보내고, 대시보드의 답과 읽은 설치 상태를 그 바인딩의 방식대로 판정한다(ADR-083).
 *
 * <p>붙이기와 값 바꾸기, 반영 맞추기가 {@link ConnectorBindingService} 안에서 함께 쓴다. 외부 호출의 실패는 부른 쪽이 다룬다.
 */
@Component
@RequiredArgsConstructor
public class ConnectorBindingInstalls {
    private final HermesConnectorClient connector;
    private final ConnectorBindingProperties properties;

    /** 설치를 그 방식대로 한 번 다시 보내고 대시보드의 답을 돌려준다. 답을 읽는 규칙은 {@link #record} 가 갖는다. */
    InstallResult sendAgain(ConnectorBinding binding, String connectorId, boolean legacy) {
        String profile = binding.agent().hermesProfile();
        if (legacy) {
            return connector.putConnector(
                    profile, connectorId, true, binding.agent().sandboxOwner());
        }
        return connector.bindConnector(
                profile,
                connectorId,
                binding.connection().vault(),
                binding.agent().sandboxOwner());
    }

    /**
     * 설치 결과를 바인딩에 적는다. 바인딩은 {@code PENDING} 이 된다.
     *
     * <p>바인딩 설치는 바뀐 것이 있을 때만 재시작이 필요하다고 답한다. 옛 설치는 설치된 커넥터에 늘 필요하다고 답하므로 그 값은
     * 쓰지 않고 hook plugin 파일이 바뀌었는지만 본다. 재시작이 필요 없는 바인딩 설치가 {@code reload_pending} 이면 반영 예정
     * 시각을 지금에서 {@code assistant.connector.binding.apply-delay} 뒤로 적는다(ADR-20261007 / connector-live-reload).
     * 옛 설치의 판정은 바꾸지 않는다.
     *
     * @return 재시작이나 반영 예정을 기다려야 해 지금 반영을 확인할 수 없는가
     */
    boolean record(ConnectorBinding binding, InstallResult installed, boolean legacy, Instant now) {
        boolean restart = legacy ? installed.pluginUpdated() : installed.restartRequired() || installed.pluginUpdated();
        binding.installed(restart, now);
        boolean scheduled = !legacy && !restart && installed.reloadPending();
        if (scheduled) {
            binding.scheduleApply(now.plus(properties.applyDelay()));
        }
        return restart || scheduled;
    }

    /** 다시 보낸 뒤 읽은 설치가 그 방식대로 반영됐는가. 일반 바인딩은 바인딩 방식으로 켜져 있어야 한다. */
    static boolean installedHere(ConnectorState state, boolean legacy) {
        boolean configured = state.configured() && state.policyHook();
        return legacy
                ? configured
                : configured && state.enabled() && HermesConnectorClient.MODE_BIND.equals(state.mode());
    }
}
