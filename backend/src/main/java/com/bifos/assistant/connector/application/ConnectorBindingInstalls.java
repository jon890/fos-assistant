package com.bifos.assistant.connector.application;

import com.bifos.assistant.browser.application.BrowserGatewayTokens;
import com.bifos.assistant.connector.application.model.ConnectorBindingAttempt;
import com.bifos.assistant.connector.application.model.ConnectorInstallResult;
import com.bifos.assistant.connector.application.model.ResyncOutcome;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.hermes.application.port.ConnectorInstallConflict;
import com.bifos.assistant.hermes.application.port.ConnectorProfileRejected;
import com.bifos.assistant.hermes.application.port.ConnectorSandboxUnavailable;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient.ConnectorState;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient.InstallResult;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient.ProbeResult;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * 불변 설치 입력으로 대시보드를 부르고 답과 설치 상태를 그 바인딩의 방식대로 판정한다(ADR-083).
 *
 * <p>외부 호출은 DB 트랜잭션 밖에서만 실행한다. 결과를 엔티티에 적는 단계는
 * {@link ConnectorInstallAttempts}가 현재 설치 시도를 대조한 결과 트랜잭션 안에서만 부른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorBindingInstalls {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String STEP_INSTALL = "install";
    private static final String STEP_INSTALL_STATE = "install-state";
    private static final String STEP_PROBE = "probe";

    private final HermesConnectorClient connector;
    private final ConnectorBindingProperties properties;
    private final BrowserGatewayTokens tokens;


    /** 설치를 그 방식대로 한 번 보내고 대시보드의 답을 돌려준다. 엔티티를 외부 호출로 넘기지 않는다. */
    private InstallResult sendAgain(ConnectorBindingAttempt attempt) {
        if (attempt.guardProtocol() != null) {
            var guard = JSON.createObjectNode().put("v", 1).put("protocol", attempt.guardProtocol())
                .put("bindingId", attempt.bindingId().toString()).put("connectionId", attempt.connectionId().toString())
                .put("connectionUpdatedAt", attempt.connectionRevision().toString())
                .put("manifestSha256", attempt.guardManifestSha256());
            return connector.bindConnector(attempt.profile(), attempt.connectorId(), attempt.vault(),
                attempt.sandboxOwner(), attempt.ownerBrowser(), guard);
        }
        return connector.bindConnector(attempt.profile(), attempt.connectorId(), attempt.vault(),
            attempt.sandboxOwner(), attempt.ownerBrowser());
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
     * <p>바인딩 설치는 바뀐 것이 있을 때만 재시작이 필요하다고 답한다. 재시작이 필요 없는 바인딩 설치가 {@code reload_pending} 이면
     * 반영 예정 시각을 지금에서 {@code assistant.connector.binding.apply-delay} 뒤로 적는다(ADR-20261007 / connector-live-reload).
     *
     * @return 재시작이나 반영 예정을 기다려야 해 지금 반영을 확인할 수 없는가
     */
    private boolean record(ConnectorBinding binding, InstallResult installed, Instant now) {
        boolean restart = installed.restartRequired() || installed.pluginUpdated();
        binding.installed(restart, now);
        // 재시작 대기는 관리자 반영 완료가 푼다. 남은 반영 예정 시각이 그 반영 완료를 막지 않게 비우고, 새로 잡지도 않는다.
        if (restart) {
            binding.clearApplyDue();
        }
        boolean scheduled = !binding.restartRequired() && installed.reloadPending();
        if (scheduled) {
            binding.scheduleApply(now.plus(properties.applyDelay()));
        }
        return restart || scheduled;
    }


    /** 현재 시도에만 설치와 probe 결과를 적는다. 이 단계는 연결의 revision을 바꾸지 않는다. */
    void record(ConnectorBinding binding, ConnectorBindingAttempt attempt, ConnectorInstallResult result) {
        Instant now = binding.updatedAt().isAfter(attempt.startedAt()) ? binding.updatedAt() : attempt.startedAt();
        if (result.installed() != null) {
            record(binding, result.installed(), now);
        }
        if (result.undeclaredTools() != null) {
            binding.connection().recordUndeclaredTools(result.undeclaredTools());
        }
        if (result.outcome() == ResyncOutcome.READY) {
            binding.ready(now);
        } else {
            binding.pending(now);
        }
    }


    /**
     * 다시 보낸 뒤 읽은 설치가 그 방식대로 반영됐는가. 일반 바인딩은 바인딩 방식으로 켜져 있어야 한다.
     *
     * @return 반영됐으면 {@code READY}, {@code policy_hook} 만 거짓이면 {@code POLICY_HOOK_OFF}, 그 밖은 {@code NOT_INSTALLED}
     */
    static ResyncOutcome notInstalledReason(ConnectorState state) {
        boolean installed = state.configured() && state.enabled() && HermesConnectorClient.MODE_BIND.equals(state.mode());
        if (!installed) {
            return ResyncOutcome.NOT_INSTALLED;
        }
        return state.policyHook() ? ResyncOutcome.READY : ResyncOutcome.POLICY_HOOK_OFF;
    }


    /**
     * 바인딩 설치와 반영 확인을 실행한다.
     *
     * <p>새 붙이기의 명시적 거절만 호출자에게 넘겨 해당 시도의 행을 지우게 한다. 그 밖의 실패는 결과로 돌려주고,
     * 실패 단계와 커넥터 번호와 예외 종류만 경고로 남긴다. 예외 메시지와 원격 응답은 남기지 않는다.
     */
    ConnectorInstallResult execute(ConnectorBindingAttempt attempt) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("installation HTTP requires no transaction");
        }
        if (attempt.waiting() != null) {
            return new ConnectorInstallResult(null, attempt.waiting(), null);
        }
        String step = STEP_INSTALL_STATE;
        InstallResult installed = null;
        try {
            step = STEP_INSTALL;
            installed = sendAgain(attempt);
            boolean restart = installed.restartRequired() || installed.pluginUpdated();
            boolean scheduled = !restart && !attempt.restartRequired() && installed.reloadPending();
            ResyncOutcome outcome = restart ? ResyncOutcome.RESTART_PENDING
                : scheduled ? ResyncOutcome.APPLY_SCHEDULED : ResyncOutcome.NOT_INSTALLED;
            if (!attempt.checkInstallation() || restart || scheduled) {
                return new ConnectorInstallResult(installed, outcome, null);
            }
            step = STEP_INSTALL_STATE;
            outcome = notInstalledReason(connector.readConnector(attempt.profile(), attempt.connectorId()));
            if (outcome != ResyncOutcome.READY) {
                return new ConnectorInstallResult(installed, outcome, null);
            }
            step = STEP_PROBE;
            ProbeResult probe = connector.probe(attempt.profile(), attempt.mcpServer());
            int undeclared = (int)probe.tools().stream().filter(name -> !attempt.declaredTools().contains(name)).count();
            if (!probe.ok()) {
                outcome = ResyncOutcome.PROBE_FAILED;
            } else if (probe.tools().isEmpty()) {
                outcome = ResyncOutcome.NO_TOOLS;
            }
            return new ConnectorInstallResult(installed, outcome, undeclared);
        } catch (ConnectorSandboxUnavailable | ConnectorInstallConflict | ConnectorProfileRejected ex) {
            if (!attempt.checkInstallation() && !attempt.replaceValues()) {
                throw ex;
            }
            warn(step, attempt.connectorId(), ex);
            return new ConnectorInstallResult(installed, ResyncOutcome.CALL_FAILED, null);
        } catch (RuntimeException ex) {
            warn(step, attempt.connectorId(), ex);
            return new ConnectorInstallResult(installed, ResyncOutcome.CALL_FAILED, null);
        }
    }


    /** 실패한 단계와 커넥터 번호와 예외 종류만 남긴다. 예외 메시지와 원격 응답에는 칸 값이 섞일 수 있어 적지 않는다. */
    static void warn(String step, String connectorId, RuntimeException ex) {
        log.warn("connector {} failed at {}: {}", connectorId, step, ex.getClass().getSimpleName());
    }
}
