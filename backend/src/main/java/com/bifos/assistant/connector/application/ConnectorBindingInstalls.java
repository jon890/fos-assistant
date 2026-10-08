package com.bifos.assistant.connector.application;

import com.bifos.assistant.browser.application.BrowserGatewayTokens;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.HermesConnectorClient.ConnectorState;
import com.bifos.assistant.hermes.HermesConnectorClient.InstallResult;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
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
    private final BrowserGatewayTokens tokens;

    /** 설치를 그 방식대로 한 번 다시 보내고 대시보드의 답을 돌려준다. 답을 읽는 규칙은 {@link #record} 가 갖는다. */
    InstallResult sendAgain(ConnectorBinding binding, ConnectorManifest manifest, boolean legacy) {
        String profile = binding.agent().hermesProfile();
        if (legacy) {
            return connector.putConnector(
                    profile, manifest.id(), true, binding.agent().sandboxOwner());
        }
        return connector.bindConnector(
                profile,
                manifest.id(),
                binding.connection().vault(),
                binding.agent().sandboxOwner(),
                ownerBrowser(binding, manifest));
    }

    /**
     * 바인딩 설치에 실을 브라우저 중계 주소다. 사용자 브라우저를 쓰지 않는 커넥터는 null 이라 본문에 키가 없다.
     *
     * <p>주소에는 그 바인딩의 표식이 들어간다. 같은 바인딩은 늘 같은 주소라 다시 설치해도 서버 정의가 바뀌지 않는다. 중계가 꺼졌으면
     * 빈 값이다. 설치는 막지 않고 커넥터가 브라우저에 닿지 못한다고 답한다(ADR-20261008 / browser-gateway-token).
     */
    String ownerBrowser(ConnectorBinding binding, ConnectorManifest manifest) {
        return manifest.ownerBrowser() ? tokens.bindingAddress(binding.id()).orElse("") : null;
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
        // 재시작 대기는 관리자 반영 완료가 푼다. 남은 반영 예정 시각이 그 반영 완료를 막지 않게 비우고, 새로 잡지도 않는다.
        if (restart) {
            binding.clearApplyDue();
        }
        boolean scheduled = !legacy && !binding.restartRequired() && installed.reloadPending();
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
