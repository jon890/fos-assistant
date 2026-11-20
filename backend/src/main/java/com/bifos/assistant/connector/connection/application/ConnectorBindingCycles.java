package com.bifos.assistant.connector.connection.application;

import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.connection.application.dto.ConnectorBindingAttempt;
import com.bifos.assistant.connector.connection.application.dto.CycleStep;
import com.bifos.assistant.connector.connection.application.dto.ResyncOutcome;
import com.bifos.assistant.connector.connection.config.ConnectorBindingProperties;
import com.bifos.assistant.connector.connection.domain.ConnectorBinding;
import com.bifos.assistant.connector.connection.domain.DueBinding;
import com.bifos.assistant.connector.connection.domain.type.CyclePhase;
import com.bifos.assistant.connector.connection.infra.ConnectorBindingRepository;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.usage.execution.application.UserExecutionLimiter;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 정의나 값이 바뀐 바인딩을 공유 gateway 재시작 없이 그 서버만 껐다 켜서 반영한다(ADR-20261120 / connector-auto-apply).
 *
 * <p>{@code IDLE_WAIT} 바인딩은 주인이 바쁘지 않거나 기다림 상한({@code cycle-idle-max})이 지나면 끄기 설치를 보낸다.
 * {@code OFF} 바인딩은 꺼진 지 {@code apply-delay} 가 지나면 보통 설치로 켠다. 켠 설치의 반영 예정은
 * {@link ConnectorBindingApplier#applyDue()} 가 확인해 {@code READY} 로 만든다. 단계는 설치의 답이 정한다.
 *
 * <p>주인이 바쁜지는 {@link UserExecutionLimiter#used} 로 본다. 바인딩이 붙은 에이전트는 비공개라 그 profile 의 실행은 모두 주인의
 * 실행이다. 주인의 다른 에이전트 실행도 세므로 기다림이 길어질 수 있지만 대화를 끊지 않는 쪽이다.
 * 자동 시도가 실패하면 실패 표시가 남고 이 컴포넌트는 그 바인딩을 다시 집지 않는다. 관리자 반영 완료가 실패 표시를 비우고 잇는다.
 *
 * <p>켜기는 예외다. 같은 설치를 다시 보낼 뿐이고 {@code READY} 는 probe 가 정하므로, 대시보드가 잠깐 내려간 동안 켜기 차례를 맞아도
 * 서버가 꺼진 채 남지 않게 {@value #ON_RETRY_LIMIT} 번까지 다음 주기에 다시 보낸다. 연속 실패 수는 이 컴포넌트의 필드(JVM 메모리)다.
 * Control Plane 이 한 대이고 예약 작업이 스레드 하나에서 돈다는 전제이며, 다시 띄우면 처음부터 센다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorBindingCycles {
    /** 켜기가 연속으로 이만큼 실패하면 실패 표시를 남긴다. 30초 주기라 5분쯤이다. */
    static final int ON_RETRY_LIMIT = 10;

    private final ConnectorBindingRepository bindings;
    private final ConnectorInstallAttempts attempts;
    private final AgentRepository agents;
    private final HermesConnectorClient connector;
    private final UserExecutionLimiter limiter;
    /** Spring Boot 가 transaction manager 로 만든 기본 설정이다. 바인딩마다 트랜잭션을 따로 연다. */
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ConnectorBindingProperties properties;

    /** 바인딩 번호마다 켜기의 연속 실패 수다. 켜기가 실패 없이 끝나거나 상한으로 표시를 남기면 지운다. */
    private final Map<Long, Integer> onFailures = new HashMap<>();


    /**
     * 껐다 켜기 단계의 바인딩마다 다음 걸음이 왔으면 설치를 한 번 보낸다.
     *
     * <p>대상의 번호와 주인이 바쁜지는 트랜잭션 밖에서 읽는다. 바인딩마다 연결 사용자 행, 에이전트 행 차례로 잠근 뒤 다시 읽어 단계와
     * 실패 표시가 그대로인지 본다. 한 바인딩의 실패는 경고로 남기고 다음 바인딩으로 간다.
     *
     * @return 설치를 보내 결과를 적용한 바인딩 수
     */
    public int advance() {
        List<DueBinding> cycling = bindings.findCycling();
        if (cycling.isEmpty()) {
            return 0;
        }
        List<ConnectorManifest> catalog = readCatalog();
        int sent = 0;
        for (DueBinding due : cycling) {
            try {
                boolean busy = limiter.used(due.userId()) > 0;
                Step step = transactions.execute(status -> advanceLocked(due, busy, catalog));
                if (step == null) {
                    continue;
                }
                if (step.attempt() == null) {
                    // 켜기 차례에 카탈로그를 읽지 못했다. 설치 실패와 같이 센다.
                    countOnFailure(due);
                    continue;
                }
                Optional<ResyncOutcome> outcome = attempts.execute(step.attempt());
                if (outcome.isPresent()) {
                    sent++;
                    if (step.attempt().cycleStep() == CycleStep.ON) {
                        if (outcome.get() == ResyncOutcome.CALL_FAILED) {
                            countOnFailure(due);
                        } else {
                            onFailures.remove(due.bindingId());
                        }
                    }
                }
            } catch (RuntimeException ex) {
                log.warn("connector binding {} failed at cycle: {}", due.bindingId(), ex.getClass().getSimpleName());
            }
        }
        return sent;
    }


    /**
     * 바인딩 하나의 트랜잭션이다. 트랜잭션 안에서만 부른다.
     *
     * @return 이번 걸음. 아직 걸음이 오지 않았거나 건너뛰었으면 null 이다
     */
    private Step advanceLocked(DueBinding due, boolean busy, List<ConnectorManifest> catalog) {
        // 잠금 순서는 연결 사용자 행 다음에 에이전트 행이다.
        attempts.lockOwner(due.userId());
        boolean target = agents.findByIdForUpdate(due.agentId())
            .filter(candidate -> !candidate.isDeleted())
            .filter(candidate -> Objects.equals(candidate.ownerUserId(), due.userId()))
            .isPresent();
        ConnectorBinding binding = bindings.findById(due.bindingId()).orElse(null);
        if (binding == null || binding.cyclePhase() == null || binding.applyFailedAt() != null) {
            return null;
        }
        Instant now = clock.instant();
        if (!target) {
            // 그대로 두면 매 주기 다시 집는다. 에이전트를 지우거나 주인이 바뀌면 바인딩도 정리된다.
            binding.clearCycle();
            bindings.save(binding);
            return null;
        }
        CycleStep step;
        if (binding.cyclePhase() == CyclePhase.IDLE_WAIT) {
            if (busy && now.isBefore(binding.cycleAt().plus(properties.cycleIdleMax()))) {
                return null;
            }
            step = CycleStep.OFF;
        } else {
            if (now.isBefore(binding.cycleAt().plus(properties.applyDelay()))) {
                return null;
            }
            step = CycleStep.ON;
        }
        if (catalog == null) {
            if (step == CycleStep.ON) {
                return new Step(null);
            }
            binding.pending(now);
            binding.markApplyFailed(now);
            bindings.save(binding);
            return null;
        }
        Optional<ConnectorManifest> manifest = catalog.stream()
            .filter(declared -> declared.id().equals(binding.connection().connectorId()))
            .findFirst();
        return new Step(attempts.begin(binding, manifest, false, false, false, now, true, step));
    }


    /** 켜기의 연속 실패를 하나 센다. 상한에 이르면 잠근 뒤 아직 꺼진 단계인 바인딩에 실패 표시를 남긴다. */
    private void countOnFailure(DueBinding due) {
        int failures = onFailures.merge(due.bindingId(), 1, Integer::sum);
        if (failures < ON_RETRY_LIMIT) {
            return;
        }
        transactions.executeWithoutResult(status -> {
            attempts.lockOwner(due.userId());
            agents.findByIdForUpdate(due.agentId());
            bindings.findById(due.bindingId())
                .filter(binding -> binding.cyclePhase() == CyclePhase.OFF && binding.applyFailedAt() == null)
                .ifPresent(binding -> {
                    Instant now = clock.instant();
                    binding.pending(now);
                    binding.markApplyFailed(now);
                    bindings.save(binding);
                });
        });
        onFailures.remove(due.bindingId());
    }


    /**
     * 한 바인딩의 이번 걸음이다.
     *
     * @param attempt 커밋한 설치 입력. 켜기 차례에 카탈로그를 읽지 못했으면 null 이다
     */
    private record Step(ConnectorBindingAttempt attempt) {
    }


    /** 카탈로그 HTTP 는 잠금을 잡기 전에 실행한다. 실패는 바인딩마다 실패 표시로 적는다. */
    private List<ConnectorManifest> readCatalog() {
        try {
            return ConnectorManifests.read(connector);
        } catch (RuntimeException ex) {
            ConnectorBindingInstalls.warn("catalog", "catalog", ex);
            return null;
        }
    }
}
