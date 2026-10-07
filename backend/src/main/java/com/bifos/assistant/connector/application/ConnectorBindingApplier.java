package com.bifos.assistant.connector.application;

import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.DueBinding;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 반영 예정 시각이 지난 바인딩의 반영 맞추기를 돌린다(ADR-20261007 / connector-live-reload). 반영 맞추기 자체는
 * {@link ConnectorBindingService#resync} 가 갖는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorBindingApplier {
    private final ConnectorBindingService service;
    private final ConnectorBindingRepository bindings;
    private final AgentRepository agents;
    private final AppUserRepository users;
    private final HermesConnectorClient connector;
    /** Spring Boot 가 transaction manager 로 만든 기본 설정이다. 바인딩마다 트랜잭션을 따로 연다. */
    private final TransactionTemplate transactions;

    private final Clock clock;

    /** 30초마다 돈다. 검사에서는 {@code -} 로 끄고 본체를 직접 부른다. */
    @Scheduled(cron = "${assistant.connector.binding.apply-cron}")
    public void runScheduled() {
        applyDue();
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
        for (DueBinding due : bindings.findApplyDue(clock.instant())) {
            try {
                if (Boolean.TRUE.equals(transactions.execute(status -> applyDueLocked(due)))) {
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
     * @return 반영 맞추기를 돌렸는가. 건너뛰었으면 거짓이다
     */
    private boolean applyDueLocked(DueBinding due) {
        // 잠금 순서는 연결 사용자 행 다음에 에이전트 행이다. 사용자가 없으면 에이전트를 잠그지 않는다.
        boolean target = users.findByIdForUpdate(due.userId()).isPresent()
                && agents.findByIdForUpdate(due.agentId())
                        .filter(candidate -> !candidate.isDeleted())
                        .filter(candidate -> Objects.equals(candidate.ownerUserId(), due.userId()))
                        .isPresent();
        ConnectorBinding binding = bindings.findById(due.bindingId()).orElse(null);
        Instant now = clock.instant();
        if (binding == null
                || binding.restartRequired()
                || binding.applyDueAt() == null
                || binding.applyDueAt().isAfter(now)) {
            return false;
        }
        binding.clearApplyDue();
        if (!target) {
            bindings.save(binding);
            return false;
        }
        String connectorId = binding.connection().connectorId();
        Optional<ConnectorManifest> manifest;
        try {
            manifest = ConnectorManifests.find(connector, connectorId);
        } catch (RuntimeException ex) {
            // 예외를 밖으로 던지면 트랜잭션이 되돌려져 비운 예정이 살아나고 매 주기 다시 집는다.
            // 단계와 커넥터 번호와 예외 종류만 남긴다. 예외 메시지에는 칸 값이 섞일 수 있다.
            log.warn(
                    "connector {} failed at {}: {}",
                    connectorId,
                    "catalog",
                    ex.getClass().getSimpleName());
            binding.pending(now);
            bindings.save(binding);
            return false;
        }
        service.resync(binding, manifest, false);
        bindings.save(binding);
        return true;
    }
}
