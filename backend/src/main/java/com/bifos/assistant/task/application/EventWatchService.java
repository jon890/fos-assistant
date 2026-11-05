package com.bifos.assistant.task.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.connector.connection.application.ConnectorEventWatches;
import com.bifos.assistant.connector.connection.domain.EventWatchBinding;
import com.bifos.assistant.connector.connection.domain.EventWatchState;
import com.bifos.assistant.connector.connection.domain.type.EventWatchResult;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.proactive.check.application.ProactiveCheckReadiness;
import com.bifos.assistant.proactive.check.application.dto.CheckBlockerCode;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.task.application.dto.EventWatchBlocker;
import com.bifos.assistant.task.application.dto.EventWatchView;
import com.bifos.assistant.task.config.EventWatchProperties;
import com.bifos.assistant.task.config.ProactiveScheduleProperties;
import com.bifos.assistant.task.config.TaskProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 에이전트 상세의 사건 감지 조회와 켜고 끄기다(ADR-20261105 / event-watch). 계약은 {@code docs/features/proactive/design.md} 의
 * 「Control Plane API(PR2)」 가 갖는다.
 *
 * <p>요청자 자신의 연결만 다룬다. 같은 에이전트에 붙은 다른 사용자의 연결은 싣지 않는다(rule-47). 켜기는 트랜잭션을 열지 않는다. 준비 판정이
 * Hermes 를 부르므로 잠금 밖에서 한 뒤 {@link ConnectorEventWatches} 의 트랜잭션을 연다. 끄기는 미룬 알림과 끄기를 한 트랜잭션에서 한다.
 */
@Service
@RequiredArgsConstructor
public class EventWatchService {

    /** 준비 상태를 확인하지 못한 것으로 바꾸는 Hermes 오류다. 매일 깨우기의 조회와 같다. */
    private static final Set<ErrorCode> RUNTIME_UNAVAILABLE = Set.of(
        ErrorCode.HERMES_UNAVAILABLE, ErrorCode.HERMES_BUSY, ErrorCode.HERMES_PROFILE_KEY_MISSING);

    private final AgentService agents;
    private final ConnectorEventWatches watches;
    private final ProactiveCheckReadiness readiness;
    private final ProactiveScheduleProperties isolation;
    private final EventWatchProperties properties;
    private final TaskProperties taskProperties;
    private final EventWatchNotices notices;
    private final TransactionTemplate transactions;
    private final Clock clock;


    /** 그 에이전트에 붙은 요청자의 연결 가운데 사건 감지를 선언한 것마다 상태를 읽는다. Hermes 장애 중에는 켤 수 없다고 보인다. */
    public List<EventWatchView> list(CurrentUser user, String agentCode) {
        Agent agent = agents.requireReadable(user, agentCode);
        return views(user, agent, displayReadiness(agent));
    }


    /**
     * 사건 감지를 켜거나 끈다. 켜기는 그 에이전트로 대화를 시작할 수 있어야 하고 막는 까닭이 없어야 한다. 끄기는 읽기 권한만 보고 Hermes 를 부르지
     * 않는다. 끄면 상태가 지워지므로 미룬 알림이 있으면 같은 트랜잭션에서 먼저 낸다.
     *
     * @param timeZone 켤 때 저장할 시간대. 비면 기본 시간대다
     * @throws ApiException {@code CONNECTOR_NOT_FOUND}(그 에이전트에 요청자의 그 연결이 없거나 사건 감지를 선언하지 않았다),
     * {@code PROACTIVE_CHECK_UNAVAILABLE}(막는 까닭이 있다), {@code TASK_SCHEDULE_INVALID}(시간대를 읽지 못했다)
     */
    public EventWatchView update(CurrentUser user, String agentCode, String connectorId, boolean enabled, String timeZone) {
        if (!enabled) {
            Agent agent = agents.requireReadable(user, agentCode);
            transactions.executeWithoutResult(status -> {
                watches.bindingsOf(user.id(), agent.id()).stream()
                    .filter(binding -> binding.enabled() && binding.connectorId().equals(connectorId))
                    .findFirst()
                    .ifPresent(binding -> notices.flushBeforeDisable(binding.bindingId(), user.id()));
                watches.disable(user.id(), agent.id(), connectorId);
            });
            return disabledView(user, agent, connectorId);
        }
        Agent agent = agents.requireStartable(user, agentCode);
        ZoneId zone = timeZone == null || timeZone.isBlank() ? taskProperties.defaultZone() : TaskSchedule.parseZone(timeZone.strip());
        List<EventWatchBlocker> checked = readinessBlockers(agent);
        if (!find(views(user, agent, checked), connectorId).blockers().isEmpty()) {
            throw new ApiException(ErrorCode.PROACTIVE_CHECK_UNAVAILABLE, "event watch cannot be enabled on this agent now");
        }
        watches.enable(user.id(), agent.id(), connectorId, zone, clock.instant());
        return find(views(user, agent, checked), connectorId);
    }


    /** 끈 뒤의 응답이다. 카탈로그를 읽지 못해도({@code CONNECTOR_UNAVAILABLE}) 끄기는 이미 커밋됐으므로 꺼진 모양만 돌려준다. */
    private EventWatchView disabledView(CurrentUser user, Agent agent, String connectorId) {
        try {
            return find(views(user, agent, displayReadiness(agent)), connectorId);
        } catch (ApiException ex) {
            if (ex.code() != ErrorCode.CONNECTOR_UNAVAILABLE) {
                throw ex;
            }
            return new EventWatchView(connectorId, connectorId, "", false, false,
                List.of(EventWatchBlocker.of(CheckBlockerCode.READINESS_UNKNOWN.name())),
                taskProperties.defaultZone().getId(), null, null, 0, false, false);
        }
    }


    private List<EventWatchView> views(CurrentUser user, Agent agent, List<EventWatchBlocker> readinessBlockers) {
        List<EventWatchView> views = new ArrayList<>();
        Instant now = clock.instant();
        for (EventWatchBinding binding : watches.bindingsOf(user.id(), agent.id())) {
            Optional<ConnectorManifest> manifest = watches.declaring(binding.connectorId());
            if (manifest.isPresent()) {
                views.add(view(binding, manifest.get(), readinessBlockers, now));
            }
        }
        return List.copyOf(views);
    }


    private EventWatchView view(
        EventWatchBinding binding, ConnectorManifest manifest, List<EventWatchBlocker> readinessBlockers, Instant now) {
        List<EventWatchBlocker> blockers = new ArrayList<>(readinessBlockers);
        if (!binding.usable()) {
            blockers.add(EventWatchBlocker.of(EventWatchBlocker.CONNECTION_NOT_READY));
        }
        if (!ConnectorEventWatches.toolAllowed(manifest)) {
            blockers.add(EventWatchBlocker.of(EventWatchBlocker.EVENT_TOOL_NOT_ALLOWED));
        }
        if (!binding.enabled() && binding.elsewhere()) {
            blockers.add(EventWatchBlocker.of(EventWatchBlocker.EVENT_WATCH_ELSEWHERE));
        }
        EventWatchState state = binding.enabled() ? EventWatchState.fromJson(binding.state()) : null;
        ZoneId zone = state == null ? taskProperties.defaultZone() : state.zone();
        return new EventWatchView(
            binding.connectorId(),
            manifest.title(),
            manifest.eventWatch().noun(),
            binding.enabled(),
            blockers.isEmpty(),
            blockers,
            zone.getId(),
            state == null ? null : state.lastCheckedAt(),
            state == null ? null : state.lastResult(),
            state == null ? 0 : state.lastNewCount(),
            state != null && dailyLimitReached(state, now),
            state != null && state.failures() >= properties.failureThreshold());
    }


    /** 오늘의 사건 실행 수가 상한에 닿았거나, 오늘 마지막 확인이 상한으로 깨우지 않았다. 사용자의 하루 상한에 닿은 날도 보인다. */
    private boolean dailyLimitReached(EventWatchState state, Instant now) {
        LocalDate today = now.atZone(state.zone()).toLocalDate();
        return state.runsOn(today) >= properties.maxRunsPerDay()
            || (state.lastResult() == EventWatchResult.DAILY_LIMIT && state.lastCheckedAt() != null
                && state.lastCheckedAt().atZone(state.zone()).toLocalDate().equals(today));
    }


    /** 사건 실행의 준비 판정이다. Hermes 를 읽지 못하면 그 예외를 그대로 올린다. */
    private List<EventWatchBlocker> readinessBlockers(Agent agent) {
        return readiness.checkEvent(agent, isolation.isolatedExecutionEnabled()).blockers().stream()
            .map(EventWatchBlocker::of)
            .toList();
    }


    /** 조회용 준비 판정이다. Hermes 장애면 확인하지 못했다는 까닭 하나로 바꿔 켤 수 없다고 보인다. */
    private List<EventWatchBlocker> displayReadiness(Agent agent) {
        try {
            return readinessBlockers(agent);
        } catch (ApiException ex) {
            if (!RUNTIME_UNAVAILABLE.contains(ex.code())) {
                throw ex;
            }
            return List.of(EventWatchBlocker.of(CheckBlockerCode.READINESS_UNKNOWN.name()));
        }
    }


    private static EventWatchView find(List<EventWatchView> views, String connectorId) {
        return views.stream()
            .filter(view -> view.connectorId().equals(connectorId))
            .findFirst()
            .orElseThrow(() -> new ApiException(ErrorCode.CONNECTOR_NOT_FOUND, "no event watch for this connector on this agent"));
    }
}
