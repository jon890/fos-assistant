package com.bifos.assistant.proactive.loop.application;

import com.bifos.assistant.proactive.loop.config.ProactiveLoopProperties;
import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.proactive.loop.application.dto.LoopSettingView;
import com.bifos.assistant.proactive.loop.application.dto.LoopScheduleState;
import com.bifos.assistant.proactive.loop.application.port.LoopScheduleSettings;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사용자가 에이전트마다 매일 루프를 켜고 끄고 쉬게 하는 설정을 관리한다. 뜻은 {@code docs/features/proactive.md} 의 「사용자 설정」 이
 * 갖는다. 켜짐과 쉬기는 사용자와 에이전트마다 하나인 CHECK 예약 작업이 갖는다({@link LoopScheduleSettings}).
 *
 * <p>설치 설정이 꺼져 있어도 끄기와 쉬기, 이미 켠 줄을 켠 채 두는 요청은 받는다. 꺼진 줄을 켜는 요청만 막는다.
 */
@Service
@RequiredArgsConstructor
public class ProactiveLoopSettingService {

    /** 쉬기의 최대 길이다. 끝없이 쉬게 하면 끄기와 구분이 없어진다. */
    static final Duration MAX_SNOOZE = Duration.ofDays(30);

    private final AgentService agents;
    private final LoopScheduleSettings schedules;
    private final LiveProperties<ProactiveLoopProperties> properties;
    private final Clock clock;
    private final TransactionTemplate transactions;


    @Transactional(readOnly = true)
    public LoopSettingView get(CurrentUser user, String agentCode) {
        Agent agent = agents.requireReadable(user, agentCode);
        LoopScheduleState state = schedules.find(user.id(), agent.id()).orElse(null);
        return view(state, clock.instant());
    }


    /**
     * 설정을 저장한다. 켜면 그 사용자와 에이전트의 CHECK 작업을 켜고(없으면 기본 시각으로 만들고) 끄면 멈춘다. 같은 줄을 동시에 저장하는
     * 요청이 제약에 걸리면 그 트랜잭션은 롤백되므로 새 트랜잭션에서 한 번 더 저장한다.
     */
    public LoopSettingView update(CurrentUser user, String agentCode, boolean enabled, Instant snoozedUntil) {
        Agent agent = enabled ? agents.requireStartable(user, agentCode) : agents.requireReadable(user, agentCode);
        if (enabled && !properties.current().enabled() && !alreadyEnabled(user.id(), agent.id())) {
            throw new ApiException(
                ErrorCode.PROACTIVE_LOOP_UNAVAILABLE, "this installation does not enable the daily loop");
        }
        Instant now = clock.instant();
        if (snoozedUntil != null && snoozedUntil.isAfter(now.plus(MAX_SNOOZE))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "snoozedUntil must be within 30 days");
        }
        Instant snooze = snoozedUntil != null && snoozedUntil.isAfter(now) ? snoozedUntil : null;
        try {
            return transactions.execute(status -> save(user.id(), agent.id(), enabled, snooze, now));
        } catch (DataIntegrityViolationException ex) {
            // 다른 요청이 같은 줄을 먼저 만들었다. 앞 트랜잭션은 롤백됐으므로 새 트랜잭션에서 그 줄을 다시 읽어 바꾼다.
            return transactions.execute(status -> save(user.id(), agent.id(), enabled, snooze, now));
        }
    }


    /** 이미 켠 줄을 켠 채 두는 요청은 설치 설정이 꺼져 있어도 받는다. 꺼진 줄을 켜는 요청만 막는다. */
    private boolean alreadyEnabled(Long userId, Long agentId) {
        return schedules.find(userId, agentId).map(LoopScheduleState::enabled).orElse(false);
    }


    /** 작업을 잠그고 바꾼다. 제약 위반이 이 트랜잭션 안에서 드러나도록 저장소가 곧바로 flush 한다. */
    private LoopSettingView save(Long userId, Long agentId, boolean enabled, Instant snooze, Instant now) {
        return view(schedules.save(userId, agentId, enabled, snooze, now), now);
    }


    private LoopSettingView view(LoopScheduleState state, Instant now) {
        boolean available = properties.current().enabled();
        if (state == null) {
            return new LoopSettingView(available, false, null);
        }
        return new LoopSettingView(available, state.enabled(), state.snoozedAt(now) ? state.snoozedUntil() : null);
    }
}
