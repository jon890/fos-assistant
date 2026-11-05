package com.bifos.assistant.task.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.connector.connection.application.ConnectorEventWatches;
import com.bifos.assistant.connector.connection.domain.EventWatchState;
import com.bifos.assistant.connector.connection.domain.EventWatchTarget;
import com.bifos.assistant.connector.connection.domain.type.EventWatchResult;
import com.bifos.assistant.hermes.application.dto.ConnectorCallError;
import com.bifos.assistant.hermes.application.dto.ConnectorEventListing;
import com.bifos.assistant.hermes.application.dto.ConnectorEventWatch;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.port.HermesEventFeedClient;
import com.bifos.assistant.proactive.check.application.ProactiveCheckService;
import com.bifos.assistant.proactive.check.application.dto.EventBatch;
import com.bifos.assistant.proactive.check.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.check.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.concurrent.BackgroundTasks;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.task.config.EventWatchProperties;
import com.bifos.assistant.task.config.ProactiveScheduleProperties;
import com.bifos.assistant.task.config.TaskProperties;
import com.bifos.assistant.task.domain.type.TaskRunStatus;
import com.bifos.assistant.task.infra.TaskRunRepository;
import com.bifos.assistant.user.application.port.SignInRevocation;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사건 감지를 켠 바인딩마다 모델 없이 새 항목을 확인하고, 있을 때만 사건 실행을 연다(ADR-20261105 / event-watch).
 *
 * <p>순서와 까닭은 {@code docs/features/proactive.md} 의 「사건 감지」 가 갖는다. 확인은 가상 스레드 하나가 바인딩을 차례로 본다. 일정
 * 스레드를 붙잡지 않기 위해서다. 한 바인딩의 실패는 그 바인딩만 건너뛴다. 기동 정리({@link TaskRunRecovery})가 끝나기 전에는 돌지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventWatchPoller {

    private static final Duration DAY = Duration.ofHours(24);
    /** 연속 실패로 세는 연결 문제다. 동시 호출 한도나 Hermes 장애의 {@code unavailable} 은 다시 연결할 일이 아니라 세지 않는다. */
    private static final Set<ConnectorCallError> CONNECTION_PROBLEMS = Set.of(ConnectorCallError.CREDENTIAL_REJECTED, ConnectorCallError.FORBIDDEN);

    private final ConnectorEventWatches watches;
    private final EventWatchNotices notices;
    private final HermesEventFeedClient feed;
    private final ProactiveCheckService proactiveChecks;
    private final ProactiveCheckRepository checks;
    private final TaskRunRepository runs;
    private final AgentService agents;
    private final AppUserRepository users;
    private final SignInRevocation revocation;
    private final EventWatchProperties properties;
    private final ProactiveScheduleProperties isolation;
    private final TaskProperties taskProperties;
    private final TaskRunRecovery recovery;
    private final TransactionTemplate transactions;
    private final BackgroundTasks backgroundTasks;
    private final Clock clock;

    private final AtomicBoolean running = new AtomicBoolean();


    @Scheduled(cron = "${assistant.event-watch.check-cron}")
    public void runScheduled() {
        if (!recovery.finished() || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            backgroundTasks.start("event-watch", () -> {
                try {
                    checkAll();
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException ex) {
            running.set(false);
            log.warn("사건 감지 확인을 시작하지 못했다", ex);
        }
    }


    /** 켜진 바인딩을 번호 순으로 하나씩 확인한다. */
    public void checkAll() {
        for (EventWatchTarget target : watches.targets()) {
            try {
                check(target);
            } catch (RuntimeException ex) {
                log.warn("사건 감지 확인이 실패했다 bindingId={}", target.bindingId(), ex);
            }
        }
    }


    /** 바인딩 하나를 확인한다. 조회는 잠금 밖에서 하고 결과를 쓸 때 다시 잠근다. */
    void check(EventWatchTarget target) {
        EventWatchState state = EventWatchState.fromJson(target.state());
        if (state == null) {
            return;
        }
        Instant now = clock.instant();
        if (state.deferred() != null) {
            transactions.executeWithoutResult(status -> notices.flushDeferred(target.bindingId(), target.userId(), now));
        }
        Optional<ConnectorManifest> manifest = declaration(target);
        Optional<CurrentUser> owner = owner(target);
        if (manifest.isEmpty() || owner.isEmpty() || !target.usable() || !ConnectorEventWatches.toolAllowed(manifest.get())) {
            update(target.bindingId(), current -> current.checked(now, EventWatchResult.BLOCKED, 0));
            return;
        }
        LocalDate today = now.atZone(state.zone()).toLocalDate();
        if (state.runsOn(today) >= properties.maxRunsPerDay() || userDailyLimitReached(owner.get(), now)) {
            update(target.bindingId(), current -> current.checked(now, EventWatchResult.DAILY_LIMIT, 0));
            return;
        }
        Instant since = state.since(properties.overlap());
        Instant queriedAt = clock.instant();
        ConnectorEventListing listing = feed.listEvents(target.connectorId(), target.profile(), since);
        if (listing == null || !listing.ok()) {
            boolean connectionProblem = listing != null && CONNECTION_PROBLEMS.contains(listing.error());
            String title = manifest.get().title();
            transactions.executeWithoutResult(status -> {
                watches.update(target.bindingId(), current -> current.failed(now, connectionProblem));
                if (connectionProblem) {
                    notices.failed(target.bindingId(), target.userId(), title, properties.failureThreshold(), now);
                }
            });
            return;
        }
        Set<String> seenIds = state.seen().stream().map(EventWatchState.Seen::id).collect(Collectors.toSet());
        List<String> fresh = listing.items().stream()
            .map(ConnectorEventListing.Item::id)
            .filter(id -> !seenIds.contains(id))
            .distinct()
            .toList();
        List<EventWatchState.Seen> nowSeen = new ArrayList<>(state.seen().stream()
            .filter(seen -> !seen.at().isBefore(queriedAt.minus(properties.overlap())))
            .toList());
        fresh.forEach(id -> nowSeen.add(new EventWatchState.Seen(id, queriedAt)));
        if (fresh.isEmpty()) {
            update(target.bindingId(), current -> current.reachable()
                .checked(now, EventWatchResult.NO_NEW, 0)
                .consumed(queriedAt, nowSeen, false, today));
            return;
        }
        wake(target, manifest.get(), owner.get(), fresh, nowSeen, queriedAt, today, now);
    }


    /** 새 항목으로 사건 실행을 연다. 지점은 살펴보기 줄과 같은 트랜잭션에서 옮긴다. 바쁘거나 막히면 옮기지 않는다. */
    private void wake(EventWatchTarget target, ConnectorManifest manifest, CurrentUser owner, List<String> fresh,
        List<EventWatchState.Seen> nowSeen, Instant queriedAt, LocalDate today, Instant now) {
        ConnectorEventWatch watch = manifest.eventWatch();
        EventBatch batch = new EventBatch(target.bindingId(), manifest.title(), watch.noun(), watch.link(), fresh.size(),
            fresh.stream().limit(properties.maxItemsPerRun()).toList());
        try {
            proactiveChecks.startEvent(owner, target.agentCode(), batch, isolation.isolatedExecutionEnabled(),
                check -> watches.update(target.bindingId(), current -> current.reachable()
                    .checked(now, EventWatchResult.WOKE, fresh.size())
                    .consumed(queriedAt, nowSeen, true, today)));
        } catch (ApiException ex) {
            boolean busy = ex.code() == ErrorCode.USER_BUSY || ex.code() == ErrorCode.CONVERSATION_BUSY;
            update(target.bindingId(), current -> current.reachable()
                .checked(now, busy ? EventWatchResult.BUSY : EventWatchResult.BLOCKED, fresh.size()));
        }
    }


    /**
     * 사용자의 하루 발화 상한(ADR-079)에 닿았는가. 예약 작업의 발화와 모델을 부른 사건 실행을 함께 센다. 모델 없이 끝난 확인은 세지 않는다.
     */
    private boolean userDailyLimitReached(CurrentUser owner, Instant now) {
        long fired = runs.countByOwnerUserIdAndCreatedAtAfterAndStatusNot(owner.id(), now.minus(DAY), TaskRunStatus.SKIPPED);
        long woke = checks.countByUserIdAndTriggerAndStartedAtAfter(owner.id(), CheckTrigger.EVENT, now.minus(DAY));
        return fired + woke >= taskProperties.maxRunsPerDay();
    }


    /** 사건 감지 선언이다. 카탈로그를 읽지 못하면 이번 확인을 막힌 것으로 둔다. */
    private Optional<ConnectorManifest> declaration(EventWatchTarget target) {
        try {
            return watches.declaring(target.connectorId());
        } catch (ApiException ex) {
            return Optional.empty();
        }
    }


    /** 주인이 아직 허용 목록에 있고 그 에이전트로 대화를 시작할 수 있으면 그 사용자다. */
    private Optional<CurrentUser> owner(EventWatchTarget target) {
        Optional<AppUser> found = users.findById(target.userId());
        if (found.isEmpty() || revocation.revoked(found.get().email())) {
            return Optional.empty();
        }
        AppUser user = found.get();
        CurrentUser owner = new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
        try {
            agents.requireStartable(owner, target.agentCode());
        } catch (ApiException ex) {
            return Optional.empty();
        }
        return Optional.of(owner);
    }


    private void update(Long bindingId, UnaryOperator<EventWatchState> change) {
        transactions.executeWithoutResult(status -> watches.update(bindingId, change));
    }
}
