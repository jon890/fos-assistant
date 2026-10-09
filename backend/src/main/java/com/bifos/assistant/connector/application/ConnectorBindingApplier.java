package com.bifos.assistant.connector.application;

import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.application.model.ResyncOutcome;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.DueBinding;
import com.bifos.assistant.connector.domain.ReadyBinding;
import com.bifos.assistant.connector.domain.type.BindingStatus;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.notification.application.NotificationService;
import com.bifos.assistant.notification.domain.NotificationTarget;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.domain.type.NotificationTargetType;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.user.application.SignInRevocation;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * 어긋난 {@code READY} 바인딩을 찾아 반영 맞추기를 돌리고, 관리자가 할 일이 남으면 그 그룹 관리자에게 알린다. 점검 위치와 바인딩마다의
 * 연속 어긋남 횟수는 이 컴포넌트의 필드(JVM 메모리)다. Control Plane 이 한 대이고 예약 작업이 스레드 하나에서 차례로 돈다는
 * 전제다. 다시 띄우면 처음부터 센다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorBindingApplier {
    /** 연속 어긋남이 이 횟수에 이르면 설치를 다시 보내지 않고 {@code PENDING} 으로만 둔다. 같은 어긋남에 설치를 되풀이해 보내지 않는다. */
    private static final int DRIFT_STREAK_LIMIT = 3;

    private static final String REINSTALLED_TITLE = "연결 설치를 다시 맞췄어요";

    private final ConnectorBindingService service;
    private final ConnectorBindingRepository bindings;
    private final AgentRepository agents;
    private final AppUserRepository users;
    private final HermesConnectorClient connector;
    /** Spring Boot 가 transaction manager 로 만든 기본 설정이다. 바인딩마다 트랜잭션을 따로 연다. */
    private final TransactionTemplate transactions;

    private final Clock clock;
    private final NotificationService notifications;
    private final SignInRevocation access;
    private final ConnectorBindingProperties properties;

    /** 다음 점검이 이 번호 뒤의 바인딩부터 읽는다. 0 이면 처음부터다. */
    private long driftCursor;

    /** 바인딩 번호마다 다시 맞춰도 연속으로 어긋난 횟수다. 어긋나지 않았거나 잠근 뒤 {@code READY} 가 아닌 것을 보거나 상한으로 {@code PENDING} 이 되면 지운다. */
    private final Map<Long, Integer> driftStreak = new HashMap<>();

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
        return resyncWithCatalog(binding, now) != null;
    }

    /**
     * 카탈로그에서 manifest 를 찾아 반영 맞추기를 돌리고 바인딩을 저장한다. 트랜잭션 안에서만 부른다.
     *
     * <p>카탈로그를 읽지 못하면 바인딩을 {@code PENDING} 으로 두고 저장한다. 예외를 밖으로 던지면 트랜잭션이 되돌려져 비운 반영 예정이
     * 살아나 매 주기 다시 집거나, 어긋난 바인딩이 {@code READY} 로 남는다. 사용자의 연결 확인이나 관리자 반영 완료가 다시 맞추게 둔다.
     *
     * @return 반영 맞추기의 결과. 카탈로그를 읽지 못해 돌리지 않았으면 null 이다
     */
    private ResyncOutcome resyncWithCatalog(ConnectorBinding binding, Instant now) {
        String connectorId = binding.connection().connectorId();
        Optional<ConnectorManifest> manifest;
        try {
            manifest = ConnectorManifests.find(connector, connectorId);
        } catch (RuntimeException ex) {
            ConnectorBindingInstalls.warn("catalog", connectorId, ex);
            binding.pending(now);
            bindings.save(binding);
            return null;
        }
        ResyncOutcome outcome = service.resync(binding, manifest, false);
        bindings.save(binding);
        return outcome;
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
     * {@code PENDING} 으로 둔 트랜잭션이 커밋되면 횟수를 지워, 관리자 반영 완료로 다시 {@code READY} 가 된 뒤 어긋나면 1 부터 센다. 한 바인딩의 실패는 경고로 남기고 다음 바인딩으로 간다.
     *
     * <p>재시작 대기나 {@code PENDING} 으로 남은 바인딩은 연결 사용자의 그룹마다 세고, 주기 끝에 그룹마다 따로 트랜잭션을 열어 차단되지
     * 않은 관리자마다 알림 한 건을 남긴다. 반영 예정 시각을 적은 바인딩은 반영 예정 확인이 맡으므로 세지 않는다. 알림은 바인딩 상태와
     * 한 트랜잭션이 아니다. 알림을 만들지 못해도 바인딩 상태는 이미 커밋돼 관리자 목록에 보이므로 경고만 남긴다.
     *
     * @return 반영 맞추기를 돌린 바인딩 수
     */
    public int resyncDrifted() {
        int batch = properties.driftBatch();
        List<ReadyBinding> ready = bindings.findReadyAfter(BindingStatus.READY, driftCursor, PageRequest.of(0, batch));
        driftCursor = ready.size() < batch ? 0L : ready.get(ready.size() - 1).bindingId();
        int resynced = 0;
        Map<Long, AdminTodo> todos = new LinkedHashMap<>();
        for (ReadyBinding candidate : ready) {
            ResyncOutcome reason;
            try {
                reason = ConnectorBindingInstalls.notInstalledReason(
                        connector.readConnector(candidate.profile(), candidate.connectorId()), candidate.legacy());
            } catch (RuntimeException ex) {
                ConnectorBindingInstalls.warn("drift-check", candidate.connectorId(), ex);
                continue;
            }
            if (reason == ResyncOutcome.READY) {
                driftStreak.remove(candidate.bindingId());
                continue;
            }
            log.warn("connector {} drifted: {}", candidate.connectorId(), reason);
            // 이번에 처리하면 몇 번째 연속 어긋남인가. 커밋된 처리만 센다.
            int streak = driftStreak.getOrDefault(candidate.bindingId(), 0) + 1;
            try {
                Drifted drifted = transactions.execute(status -> resyncDriftedLocked(candidate, streak));
                if (drifted == null) {
                    continue;
                }
                if (drifted.counted()) {
                    if (streak >= DRIFT_STREAK_LIMIT) {
                        // 상한으로 PENDING 이 됐다. 관리자 반영 완료로 READY 가 된 뒤 다시 어긋나면 1 부터 센다.
                        driftStreak.remove(candidate.bindingId());
                    } else {
                        driftStreak.put(candidate.bindingId(), streak);
                    }
                }
                if (drifted.resynced()) {
                    resynced++;
                }
                if (drifted.adminTodo()) {
                    todos.merge(drifted.groupId(), new AdminTodo(1, drifted.restartRequired()), AdminTodo::plus);
                }
            } catch (RuntimeException ex) {
                log.warn(
                        "connector binding {} failed at drift: {}",
                        candidate.bindingId(),
                        ex.getClass().getSimpleName());
            }
        }
        todos.forEach(this::notifyAdmins);
        return resynced;
    }

    /**
     * 어긋난 바인딩 하나의 트랜잭션이다. 트랜잭션 안에서만 부른다.
     *
     * @return 그 바인딩에 한 일. 건너뛰었으면 null 이다
     */
    private Drifted resyncDriftedLocked(ReadyBinding candidate, int streak) {
        // 잠금 순서는 연결 사용자 행 다음에 에이전트 행이다. 사용자가 없으면 에이전트를 잠그지 않는다.
        AppUser owner = users.findByIdForUpdate(candidate.userId()).orElse(null);
        boolean target = owner != null
                && agents.findByIdForUpdate(candidate.agentId())
                        .filter(found -> !found.isDeleted())
                        .filter(found -> Objects.equals(found.ownerUserId(), candidate.userId()))
                        .isPresent();
        ConnectorBinding binding = bindings.findById(candidate.bindingId()).orElse(null);
        if (binding == null || binding.status() != BindingStatus.READY) {
            // 지워졌거나 다른 경로가 상태를 바꿨다. 이어지던 어긋남이 아니므로 횟수를 지운다.
            driftStreak.remove(candidate.bindingId());
            return null;
        }
        if (!target) {
            return null;
        }
        Instant now = clock.instant();
        if (streak >= DRIFT_STREAK_LIMIT) {
            binding.pending(now);
            bindings.save(binding);
            return Drifted.pending(owner.groupId(), binding, true);
        }
        ResyncOutcome outcome = resyncWithCatalog(binding, now);
        if (outcome == null) {
            return Drifted.pending(owner.groupId(), binding, false);
        }
        boolean adminTodo = outcome != ResyncOutcome.READY && outcome != ResyncOutcome.APPLY_SCHEDULED;
        return new Drifted(owner.groupId(), true, adminTodo, binding.restartRequired(), true);
    }

    /** 한 그룹의 차단되지 않은 관리자마다 알림 한 건을 남긴다. 그룹마다 트랜잭션을 따로 연다. */
    private void notifyAdmins(Long groupId, AdminTodo todo) {
        String body = todo.restartRequired()
                ? "커넥터 정의가 바뀌어 연결 " + todo.count() + "개를 다시 설치했어요. 공유 gateway 를 재시작한 뒤 「연결 반영 확인」에서 반영 완료를 눌러 주세요."
                : "커넥터 정의가 바뀐 연결 " + todo.count() + "개의 반영을 확인하지 못했어요. 「연결 반영 확인」에서 반영 완료를 눌러 다시 확인해 주세요.";
        try {
            transactions.executeWithoutResult(status -> {
                for (AppUser admin : users.findByGroupIdAndRole(groupId, UserRole.ADMIN)) {
                    if (!access.revoked(admin.email())) {
                        notifications.notify(
                                admin.id(),
                                NotificationKind.CONNECTOR_REINSTALLED,
                                REINSTALLED_TITLE,
                                body,
                                new NotificationTarget(NotificationTargetType.ADMIN_CONNECTIONS, null));
                    }
                }
            });
        } catch (RuntimeException ex) {
            log.warn(
                    "connector drift notification failed for group {}: {}",
                    groupId,
                    ex.getClass().getSimpleName());
        }
    }

    /**
     * 어긋난 바인딩 하나에 한 일이다.
     *
     * @param groupId 잠근 연결 사용자의 그룹
     * @param resynced 반영 맞추기를 돌렸는가
     * @param adminTodo 관리자가 할 일이 남았는가. 재시작 대기나 {@code PENDING} 으로 남았다
     * @param restartRequired 재시작 대기인가
     * @param counted 연속 어긋남 횟수를 바꾸는가. 반영 맞추기를 돌렸으면 올리고, 연속 상한이라 {@code PENDING} 으로만 뒀으면 지운다
     */
    private record Drifted(
            Long groupId, boolean resynced, boolean adminTodo, boolean restartRequired, boolean counted) {

        /** 반영 맞추기를 돌리지 않고 {@code PENDING} 으로만 둔 바인딩이다. */
        static Drifted pending(Long groupId, ConnectorBinding binding, boolean counted) {
            return new Drifted(groupId, false, true, binding.restartRequired(), counted);
        }
    }

    /**
     * 한 그룹에서 관리자가 할 일이 남은 바인딩 수다.
     *
     * @param restartRequired 그 가운데 재시작 대기가 하나라도 있는가
     */
    private record AdminTodo(int count, boolean restartRequired) {

        AdminTodo plus(AdminTodo other) {
            return new AdminTodo(count + other.count, restartRequired || other.restartRequired);
        }
    }
}
