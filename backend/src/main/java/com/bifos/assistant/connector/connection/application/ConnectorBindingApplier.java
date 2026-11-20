package com.bifos.assistant.connector.connection.application;

import com.bifos.assistant.connector.connection.config.ConnectorBindingProperties;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.connection.application.dto.ResyncOutcome;
import com.bifos.assistant.connector.connection.application.dto.ConnectorBindingAttempt;
import com.bifos.assistant.connector.connection.application.dto.CycleStep;
import com.bifos.assistant.connector.connection.domain.ConnectorBinding;
import com.bifos.assistant.connector.connection.domain.DueBinding;
import com.bifos.assistant.connector.connection.domain.ReadyBinding;
import com.bifos.assistant.connector.connection.domain.SkippedSkill;
import com.bifos.assistant.connector.connection.domain.type.BindingStatus;
import com.bifos.assistant.connector.connection.infra.ConnectorBindingRepository;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 반영 예정 시각이 지난 바인딩의 반영 맞추기를 돌린다(ADR-20261007 / connector-live-reload). 반영 맞추기 자체는
 * {@link ConnectorBindingService#resync} 가 갖는다.
 *
 * <p>정의 어긋남 점검도 맡는다(ADR-20261009 / connector-install-drift). manifest 가 바뀐 뒤 설치가 다시 보내지지 않아 설치 상태가
 * 어긋난 {@code READY} 바인딩을 찾아 반영 맞추기를 돌린다. 관리자 알림은 {@link ConnectorBindingAlerts} 가 실패 표시와 대기 시각으로 보낸다. 점검 위치와 바인딩마다의
 * 연속 어긋남 횟수는 이 컴포넌트의 필드(JVM 메모리)다. Control Plane 이 한 대이고 예약 작업이 스레드 하나에서 차례로 돈다는
 * 전제다. 다시 띄우면 처음부터 센다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorBindingApplier {
    /** 연속 어긋남이 이 횟수에 이르면 설치를 다시 보내지 않고 {@code PENDING} 으로만 둔다. 같은 어긋남에 설치를 되풀이해 보내지 않는다. */
    private static final int DRIFT_STREAK_LIMIT = 3;

    private final ConnectorBindingService service;
    private final ConnectorBindingRepository bindings;
    private final AgentRepository agents;
    private final HermesConnectorClient connector;
    /** Spring Boot 가 transaction manager 로 만든 기본 설정이다. 바인딩마다 트랜잭션을 따로 연다. */
    private final TransactionTemplate transactions;

    private final Clock clock;
    private final ConnectorBindingProperties properties;
    private final ConnectorInstallAttempts attempts;
    /** 껐다 켜기 단계를 진행한다(ADR-20261120 / connector-auto-apply). 반영 예정 확인과 같은 주기에 돈다. */
    private final ConnectorBindingCycles cycles;
    /** 공유 gateway 가 다시 뜬 뒤 재시작 대기 바인딩을 반영 맞춘다(ADR-20261120 / connector-auto-apply). 같은 주기에 돈다. */
    private final ConnectorGatewayRestarts restarts;
    /** 실패했거나 오래 남은 대기를 그룹 관리자에게 알린다(ADR-20261120 / connector-auto-apply). 같은 주기의 마지막에 돈다. */
    private final ConnectorBindingAlerts alerts;

    /** 다음 점검이 이 번호 뒤의 바인딩부터 읽는다. 0 이면 처음부터다. */
    private long driftCursor;

    /**
     * 바인딩 번호마다 다시 맞춰도 연속으로 어긋난 횟수다. 어긋나지 않았거나 잠근 뒤 {@code READY} 가 아닌 것을 보거나 상한으로
     * {@code PENDING} 이 되면 지운다. 카탈로그를 읽지 못한 주기는 횟수를 바꾸지 않는다. 그 바인딩은 관리자 반영 완료가 설치를 다시
     * 보낸다.
     */
    private final Map<Long, Integer> driftStreak = new HashMap<>();


    /** 30초마다 돈다. 검사에서는 {@code -} 로 끄고 본체를 직접 부른다. */
    @Scheduled(cron = "${assistant.connector.binding.apply-cron}")
    public void runScheduled() {
        applyDue();
        cycles.advance();
        restarts.detect();
        alerts.notifyDue();
    }


    /**
     * 반영 예정 시각이 지난 바인딩마다 반영 맞추기를 한 번 돌린다.
     *
     * <p>대상의 번호는 트랜잭션 밖에서 읽는다. 바인딩마다 트랜잭션을 열고 연결 사용자 행 다음에 에이전트 행을 잠근 뒤 바인딩을
     * 다시 읽는다. 첫 읽기가 잠금이어야 하는 까닭은 {@link ConnectorBindingService#confirmApplied} 가 적는다. 바인딩이
     * 지워졌거나, 그 사이 재시작 대기가 됐거나 예정 시각이 바뀌었으면 건너뛴다.
     *
     * <p>시도하기 전에 예정 시각을 비운다. 확인은 한 번만 한다. 연결 사용자가 없거나, 에이전트가 지워졌거나, 에이전트 주인이
     * 연결 사용자와 다르면 예정만 비우고 건너뛴다. 그대로 두면 매 주기 다시 집기 때문이다. 카탈로그를 읽지 못하면 그 바인딩을
     * {@code PENDING} 으로 두고 예정을 비운 채 커밋한다. 다시 보낸 설치가 또 {@code reload_pending} 이면
     * {@link ConnectorBindingService#resync} 가 새 시각을 적고, probe 가 실패해 {@code PENDING} 이 되면 사용자의 연결 확인이나
     * 관리자 반영 완료가 다시 맞춘다. 한 바인딩의 실패는 경고로 남기고 다음 바인딩으로 간다.
     *
     * @return 반영 맞추기를 돌린 바인딩 수
     */
    public int applyDue() {
        int applied = 0;
        List<ConnectorManifest> catalog = readCatalog();
        for (DueBinding due : bindings.findApplyDue(clock.instant())) {
            try {
                ConnectorBindingAttempt attempt = transactions.execute(status -> applyDueLocked(due, catalog));
                if (attempt != null && service.execute(attempt).isPresent()) {
                    applied++;
                }
            } catch (RuntimeException ex) {
                log.warn(
                    "connector binding {} failed at apply-due: {}",
                    due.bindingId(),
                    ex.getClass().getSimpleName());
            }
        }
        return applied;
    }


    /**
     * 예약 확인 하나의 트랜잭션이다. 트랜잭션 안에서만 부른다.
     *
     * @return 커밋한 설치 입력. 건너뛰었으면 null이다
     */
    private ConnectorBindingAttempt applyDueLocked(DueBinding due, List<ConnectorManifest> catalog) {
        // 잠금 순서는 연결 사용자 행 다음에 에이전트 행이다. 사용자가 없으면 에이전트를 잠그지 않는다.
        service.lockOwner(due.userId());
        boolean target = agents.findByIdForUpdate(due.agentId())
            .filter(candidate -> !candidate.isDeleted())
            .filter(candidate -> Objects.equals(candidate.ownerUserId(), due.userId()))
            .isPresent();
        ConnectorBinding binding = bindings.findById(due.bindingId()).orElse(null);
        Instant now = clock.instant();
        if (binding == null
            || binding.restartRequired()
            || binding.applyDueAt() == null
            || binding.applyDueAt().isAfter(now)) {
            return null;
        }
        binding.clearApplyDue();
        if (!target) {
            bindings.save(binding);
            return null;
        }
        if (catalog == null) {
            binding.pending(now);
            binding.markApplyFailed(now);
            bindings.save(binding);
            return null;
        }
        return automatic(binding, manifest(catalog, binding.connection().connectorId()), now);
    }


    /** 예약 작업의 반영 맞추기다. 자동 시도라 실패하면 실패 표시를 남기고, 껐다 켜기 단계의 바인딩에는 설치를 보내지 않는다. */
    private ConnectorBindingAttempt automatic(ConnectorBinding binding, Optional<ConnectorManifest> manifest, Instant now) {
        return attempts.begin(binding, manifest, true, false, false, now, true, CycleStep.NONE);
    }


    /** 카탈로그 HTTP는 사용자와 에이전트 잠금을 잡기 전에 실행한다. 실패는 대상별 시작 트랜잭션에서 PENDING으로 적는다. */
    private List<ConnectorManifest> readCatalog() {
        try {
            return ConnectorManifests.read(connector);
        } catch (RuntimeException ex) {
            ConnectorBindingInstalls.warn("catalog", "catalog", ex);
            return null;
        }
    }


    private static Optional<ConnectorManifest> manifest(List<ConnectorManifest> catalog, String connectorId) {
        return catalog.stream().filter(declared -> declared.id().equals(connectorId)).findFirst();
    }


    /** 10분마다 돈다. 검사에서는 {@code -} 로 끄고 본체를 직접 부른다. */
    @Scheduled(cron = "${assistant.connector.binding.drift-cron}")
    public void runDriftScheduled() {
        resyncDrifted();
    }


    /**
     * 설치 상태가 어긋난 {@code READY} 바인딩의 반영 맞추기를 한 번 돌린다.
     *
     * <p>{@code READY} 바인딩을 점검 위치 뒤부터 번호 순으로 {@code drift-batch} 개까지 읽는다. 받은 수가 상한보다 적으면 다음 주기는
     * 처음부터다. 바인딩마다 트랜잭션 밖에서 설치 상태를 읽는다. 사용자 행과 에이전트 행 잠금을 쥔 채 대시보드를 부르지 않기 위해서다.
     * 읽지 못하면 경고만 남기고 건너뛴다. 반영 맞추기의 설치 판정을 통과하면 어긋나지 않은 것이라 연속 횟수를 지운다.
     *
     * <p>어긋났으면 트랜잭션을 열어 {@link #applyDue} 와 같은 차례로 잠근 뒤 바인딩을 다시 읽는다. 지워졌거나 그 사이 {@code READY} 가
     * 아니게 됐으면 연속 횟수를 지우고 건너뛴다. 잠금 확인이 실패하면 횟수를 그대로 두고 건너뛴다. 다시 맞춘 바인딩은 {@code READY} 가
     * 아니어서 다음 주기의 대상이 아니다. 다시 맞춰 {@code READY} 가 됐는데 이번에 처리하면 연속 {@value #DRIFT_STREAK_LIMIT} 번째
     * 어긋남이면 설치를 보내지 않고 {@code PENDING} 으로만 둔다. 연속 횟수는 반영 맞추기를 돌린 트랜잭션이 커밋된 뒤에만 올린다. 상한이라
     * {@code PENDING} 으로 둔 트랜잭션이 커밋되면 횟수를 지워, 관리자 다시 확인으로 다시 {@code READY} 가 된 뒤 어긋나면 1 부터 센다. 한 바인딩의 실패는 경고로 남기고 다음 바인딩으로 간다.
     *
     * <p>관리자 알림은 여기서 보내지 않는다. 연속 상한이나 카탈로그 실패로 {@code PENDING} 이 된 바인딩은 실패 표시가, 재시작 대기나 껐다
     * 켜기가 된 바인딩은 대기 시각이 {@link ConnectorBindingAlerts} 의 대상이 된다(ADR-20261120 / connector-auto-apply).
     *
     * @return 반영 맞추기를 돌린 바인딩 수
     */
    public int resyncDrifted() {
        int batch = properties.driftBatch();
        List<ReadyBinding> ready = bindings.findReadyAfter(BindingStatus.READY, driftCursor, PageRequest.of(0, batch));
        driftCursor = ready.size() < batch ? 0L : ready.get(ready.size() - 1).bindingId();
        int resynced = 0;
        for (ReadyBinding candidate : ready) {
            ResyncOutcome reason;
            HermesConnectorClient.ConnectorState state;
            try {
                state = connector.readConnector(candidate.profile(), candidate.connectorId());
                reason = ConnectorBindingInstalls.notInstalledReason(state);
            } catch (RuntimeException ex) {
                ConnectorBindingInstalls.warn("drift-check", candidate.connectorId(), ex);
                continue;
            }
            if (reason == ResyncOutcome.READY) {
                driftStreak.remove(candidate.bindingId());
                syncSkippedSkills(candidate, state);
                continue;
            }
            log.warn("connector {} drifted: {}", candidate.connectorId(), reason);
            // 이번에 처리하면 몇 번째 연속 어긋남인가. 커밋된 처리만 센다.
            int streak = driftStreak.getOrDefault(candidate.bindingId(), 0) + 1;
            try {
                List<ConnectorManifest> catalog = readCatalog();
                Drifted drifted = transactions.execute(status -> resyncDriftedLocked(candidate, streak, catalog));
                if (drifted == null) {
                    driftStreak.remove(candidate.bindingId());
                    continue;
                }
                if (drifted.attempt() != null) {
                    // 결과가 버려졌거나 그 사이 바인딩이 지워졌으면 세지 않는다.
                    if (service.execute(drifted.attempt()).isEmpty() || !bindings.existsById(candidate.bindingId())) {
                        continue;
                    }
                    drifted = new Drifted(true, true, null);
                }
                if (drifted.counted()) {
                    if (streak >= DRIFT_STREAK_LIMIT) {
                        // 상한으로 PENDING 이 됐다. 관리자 다시 확인으로 READY 가 된 뒤 다시 어긋나면 1 부터 센다.
                        driftStreak.remove(candidate.bindingId());
                    } else {
                        driftStreak.put(candidate.bindingId(), streak);
                    }
                }
                if (drifted.resynced()) {
                    resynced++;
                }
            } catch (RuntimeException ex) {
                log.warn(
                    "connector binding {} failed at drift: {}",
                    candidate.bindingId(),
                    ex.getClass().getSimpleName());
            }
        }
        return resynced;
    }


    /**
     * 대시보드가 지금 기준으로 답한 빠진 에이전트용 스킬을 바인딩 기록에 맞춘다.
     *
     * <p>이름 겹침은 설치 상태 판정이 어긋남으로 보지 않아 재설치가 없다. 설치 응답에서만 기록하면 그 겹침으로 빠진
     * 스킬의 까닭이 어디에도 남지 않는다. 옛 대시보드 plugin 은 칸을 내지 않아 null 이므로 기록을 지우지 않는다.
     * 기록이 이미 같으면 쓰지 않는다. 실패는 경고로 남기고 다음 바인딩으로 간다.
     */
    private void syncSkippedSkills(ReadyBinding candidate, HermesConnectorClient.ConnectorState state) {
        if (state.skippedSkills() == null) {
            return;
        }
        List<SkippedSkill> current = state.skippedSkills().stream()
            .map(skipped -> new SkippedSkill(skipped.name(), skipped.reasons()))
            .toList();
        try {
            transactions.executeWithoutResult(status -> {
                service.lockOwner(candidate.userId());
                agents.findByIdForUpdate(candidate.agentId());
                bindings.findById(candidate.bindingId())
                    .filter(binding -> binding.status() == BindingStatus.READY)
                    .filter(binding -> !binding.skippedSkills().equals(current))
                    .ifPresent(binding -> {
                        binding.recordSkippedSkills(current);
                        bindings.save(binding);
                    });
            });
        } catch (RuntimeException ex) {
            log.warn(
                "connector binding {} failed to record skipped skills: {}",
                candidate.bindingId(),
                ex.getClass().getSimpleName());
        }
    }


    /**
     * 어긋난 바인딩 하나의 트랜잭션이다. 트랜잭션 안에서만 부른다.
     *
     * @return 그 바인딩에 한 일. 건너뛰었으면 null 이다
     */
    private Drifted resyncDriftedLocked(ReadyBinding candidate, int streak, List<ConnectorManifest> catalog) {
        // 잠금 순서는 연결 사용자 행 다음에 에이전트 행이다. 사용자가 없으면 에이전트를 잠그지 않는다.
        service.lockOwner(candidate.userId());
        boolean target = agents.findByIdForUpdate(candidate.agentId())
            .filter(found -> !found.isDeleted())
            .filter(found -> Objects.equals(found.ownerUserId(), candidate.userId()))
            .isPresent();
        ConnectorBinding binding = bindings.findById(candidate.bindingId()).orElse(null);
        if (binding == null || binding.status() != BindingStatus.READY) {
            // 지워졌거나 다른 경로가 상태를 바꿨다. 이어지던 어긋남이 아니므로 횟수를 지운다.
            return null;
        }
        if (!target) {
            return new Drifted(false, false, null);
        }
        Instant now = clock.instant();
        if (streak >= DRIFT_STREAK_LIMIT || catalog == null) {
            binding.pending(now);
            binding.markApplyFailed(now);
            bindings.save(binding);
            return new Drifted(false, streak >= DRIFT_STREAK_LIMIT, null);
        }
        return new Drifted(false, false, automatic(binding, manifest(catalog, candidate.connectorId()), now));
    }


    /**
     * 어긋난 바인딩 하나에 한 일이다.
     *
     * @param resynced 반영 맞추기를 돌렸는가
     * @param counted 연속 어긋남 횟수를 바꾸는가. 반영 맞추기를 돌렸으면 올리고, 연속 상한이라 {@code PENDING} 으로만 뒀으면 지운다
     * @param attempt 커밋한 설치 입력. 설치를 보내지 않는 처리면 null
     */
    private record Drifted(boolean resynced, boolean counted, ConnectorBindingAttempt attempt) {
    }
}
