package com.bifos.assistant.connector.connection.application;

import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.connection.application.dto.ConnectorBindingAttempt;
import com.bifos.assistant.connector.connection.application.dto.CycleStep;
import com.bifos.assistant.connector.connection.domain.ConnectorBinding;
import com.bifos.assistant.connector.connection.domain.DueBinding;
import com.bifos.assistant.connector.connection.infra.ConnectorBindingRepository;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.hermes.application.port.HermesGatewayClient;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 공유 gateway 가 다시 뜬 것을 알아채고 그 전에 재시작 대기가 된 바인딩을 사람 없이 반영 맞춘다(ADR-20261120 / connector-auto-apply).
 *
 * <p>대시보드 plugin 이 답하는 gateway 기동 시각과 바인딩의 재시작 대기 시각(결과 트랜잭션 시각)을 비교한다. 대기 시각이 기동 시각보다
 * {@link #CLOCK_SLACK} 넘게 앞서면 그 gateway 는 대기를 만든 설치의 파일을 읽고 떴다. 마지막으로 본 기동 시각은 저장하지 않는다. 배포는
 * Control Plane 과 gateway 를 함께 다시 띄우므로 메모리의 이전 값과 비교하면 배포 때의 재시작을 놓친다.
 *
 * <p>기동 시각을 모르면 아무것도 바꾸지 않는다. 짐작으로 {@code READY} 를 만들지 않는다(스펙 DECISION2). 모르는 상태는 이 컴포넌트의
 * 필드(JVM 메모리)로 들고 바뀔 때만 로그를 한 줄 남긴다. 처음 값은 「읽힌다」 라 다시 띄운 뒤의 첫 실패도 경고가 남는다. Control Plane
 * 이 한 대이고 예약 작업이 스레드 하나에서 돈다는 전제다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorGatewayRestarts {
    /** 기동 시각의 정밀도(1초)와 {@code btime} 의 반올림을 덮는 여유다. */
    static final Duration CLOCK_SLACK = Duration.ofSeconds(2);

    private final ConnectorBindingRepository bindings;
    private final ConnectorInstallAttempts attempts;
    private final AgentRepository agents;
    private final HermesConnectorClient connector;
    private final HermesGatewayClient gateway;
    /** Spring Boot 가 transaction manager 로 만든 기본 설정이다. 바인딩마다 트랜잭션을 따로 연다. */
    private final TransactionTemplate transactions;
    private final Clock clock;

    /** 마지막으로 기동 시각을 읽었는가. 바뀔 때만 로그를 남긴다. */
    private volatile boolean gatewayKnown = true;


    /** 그 대기 시각 뒤에 gateway 가 다시 떴는가. 재시작 감지와 관리자 다시 확인이 함께 쓴다. */
    static boolean restartedAfter(Instant since, Instant startedAt) {
        return since != null && since.isBefore(startedAt.minus(CLOCK_SLACK));
    }


    /**
     * 재시작 대기 바인딩이 있으면 gateway 기동 시각을 한 번 읽고, 그 전에 대기가 된 바인딩마다 반영 맞추기를 한 번 돌린다.
     *
     * <p>대상의 번호는 트랜잭션 밖에서 읽는다. 재시작 대기가 없으면 대시보드를 부르지 않고, 기동 시각보다 앞선 대기가 없으면 카탈로그를 읽지 않는다. 바인딩마다 연결 사용자 행, 에이전트 행 차례로 잠근
     * 뒤 다시 읽어 조건이 그대로인지 본다. 반영 맞추기는 자동 경로라 실패하면 실패 표시가 남고 다시 집지 않는다. 카탈로그를 읽지 못한 주기는
     * 건너뛴다. 재시작 대기는 그대로 남아 다음 주기가 맡는다. 한 바인딩의 실패는 경고로 남기고 다음 바인딩으로 간다.
     *
     * @return 반영 맞추기를 돌려 결과를 적용한 바인딩 수
     */
    public int detect() {
        if (bindings.findRestartWaiting(clock.instant()).isEmpty()) {
            return 0;
        }
        Optional<Instant> startedAt = startedAt();
        if (startedAt.isEmpty()) {
            return 0;
        }
        // 그 gateway 뒤에 대기가 생긴 바인딩만 남았으면 카탈로그를 읽지 않는다. 다음 재시작까지 매 주기 읽지 않으려는 것이다.
        List<DueBinding> waiting = bindings.findRestartWaitingBefore(clock.instant(), startedAt.get().minus(CLOCK_SLACK));
        if (waiting.isEmpty()) {
            return 0;
        }
        List<ConnectorManifest> catalog = readCatalog();
        if (catalog == null) {
            return 0;
        }
        int applied = 0;
        for (DueBinding due : waiting) {
            try {
                ConnectorBindingAttempt attempt = transactions.execute(status -> detectLocked(due, startedAt.get(), catalog));
                if (attempt != null && attempts.execute(attempt).isPresent()) {
                    applied++;
                }
            } catch (RuntimeException ex) {
                log.warn("connector binding {} failed at restart-detect: {}", due.bindingId(), ex.getClass().getSimpleName());
            }
        }
        return applied;
    }


    /** 관리자 다시 확인이 잠그기 전에 기동 시각을 읽는다. 요청 스레드가 부르므로 예약 작업의 로그 상태를 바꾸지 않는다. */
    public Optional<Instant> readStartedAt() {
        return gateway.startedAt();
    }


    /**
     * 재시작 대기 줄을 다시 확인하기 전에 그 대기 뒤에 gateway 가 다시 떴는지 본다(스펙 DECISION19). 기동 시각을 모르면 지금처럼 진행한다.
     * 껐다 켜기 단계는 재시작이 아니라 그 단계의 다음 설치로 풀리므로 보지 않는다.
     *
     * @throws ApiException {@code CONNECTOR_RESTART_NOT_DONE}. 아무것도 바꾸지 않게 시작 트랜잭션 안에서 부른다
     */
    public void requireRestarted(ConnectorBinding binding, Optional<Instant> startedAt) {
        Instant since = binding.restartRequiredSince();
        if (binding.restartRequired() && binding.cyclePhase() == null && since != null && startedAt.isPresent()
            && !restartedAfter(since, startedAt.get())) {
            throw new ApiException(ErrorCode.CONNECTOR_RESTART_NOT_DONE, "the shared gateway has not restarted since the wait");
        }
    }


    /**
     * 공유 gateway 의 기동 시각을 읽는다. 읽히던 것이 못 읽히게 되면 경고를, 다시 읽히면 info 를 한 줄 남긴다.
     *
     * @return 기동 시각. 모르면 비어 있다
     */
    Optional<Instant> startedAt() {
        Optional<Instant> startedAt = gateway.startedAt();
        if (startedAt.isEmpty() && gatewayKnown) {
            log.warn("shared gateway start time is unknown; restart-waiting bindings stay pending");
        } else if (startedAt.isPresent() && !gatewayKnown) {
            log.info("shared gateway start time is readable again");
        }
        gatewayKnown = startedAt.isPresent();
        return startedAt;
    }


    /**
     * 바인딩 하나의 트랜잭션이다. 트랜잭션 안에서만 부른다.
     *
     * @return 커밋한 설치 입력. 건너뛰었으면 null 이다
     */
    private ConnectorBindingAttempt detectLocked(DueBinding due, Instant startedAt, List<ConnectorManifest> catalog) {
        // 잠금 순서는 연결 사용자 행 다음에 에이전트 행이다.
        attempts.lockOwner(due.userId());
        boolean target = agents.findByIdForUpdate(due.agentId())
            .filter(candidate -> !candidate.isDeleted())
            .filter(candidate -> Objects.equals(candidate.ownerUserId(), due.userId()))
            .isPresent();
        ConnectorBinding binding = bindings.findById(due.bindingId()).orElse(null);
        Instant now = clock.instant();
        if (!target || binding == null || !binding.restartRequired() || binding.applyFailedAt() != null
            || binding.cyclePhase() != null || binding.applyDueAt() != null && binding.applyDueAt().isAfter(now)
            || !restartedAfter(binding.restartRequiredSince(), startedAt)) {
            return null;
        }
        Optional<ConnectorManifest> manifest = catalog.stream()
            .filter(declared -> declared.id().equals(binding.connection().connectorId()))
            .findFirst();
        return attempts.begin(binding, manifest, true, true, false, now, true, CycleStep.NONE);
    }


    /** 카탈로그 HTTP 는 잠금을 잡기 전에 실행한다. 읽지 못하면 이번 주기를 건너뛴다. */
    private List<ConnectorManifest> readCatalog() {
        try {
            return ConnectorManifests.read(connector);
        } catch (RuntimeException ex) {
            ConnectorBindingInstalls.warn("catalog", "catalog", ex);
            return null;
        }
    }
}
