package com.bifos.assistant.proactive.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.proactive.application.model.LoopSettingView;
import com.bifos.assistant.proactive.domain.ProactiveLoopSetting;
import com.bifos.assistant.proactive.infra.ProactiveLoopSettingRepository;
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
 * 사용자가 에이전트마다 매일 루프를 켜고 끄고 쉬게 하는 설정을 관리한다. 뜻은 {@code docs/backend/proactive-loop.md} 의 「사용자 설정」 이
 * 갖는다.
 *
 * <p>설치 설정이 꺼져 있어도 끄기와 쉬기는 받는다. 켜기만 막는다.
 */
@Service
@RequiredArgsConstructor
public class ProactiveLoopSettingService {

    /** 쉬기의 최대 길이다. 끝없이 쉬게 하면 끄기와 구분이 없어진다. */
    static final Duration MAX_SNOOZE = Duration.ofDays(30);

    private final AgentService agents;
    private final ProactiveLoopSettingRepository settings;
    private final LiveProperties<ProactiveLoopProperties> properties;
    private final Clock clock;
    private final TransactionTemplate transactions;

    @Transactional(readOnly = true)
    public LoopSettingView get(CurrentUser user, String agentCode) {
        Agent agent = agents.requireReadable(user, agentCode);
        ProactiveLoopSetting row =
                settings.findByUserIdAndAgentId(user.id(), agent.id()).orElse(null);
        return view(row, clock.instant());
    }

    /**
     * 설정을 저장한다. 같은 사용자와 에이전트의 처음 저장이 동시에 오면 늦은 쪽이 유일 제약에 걸린다. 그 트랜잭션은 롤백되므로 새 트랜잭션에서 먼저
     * 저장된 줄을 다시 읽어 바꾼다.
     */
    public LoopSettingView update(CurrentUser user, String agentCode, boolean enabled, Instant snoozedUntil) {
        Agent agent = enabled ? agents.requireStartable(user, agentCode) : agents.requireReadable(user, agentCode);
        if (enabled && !properties.current().enabled()) {
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

    /** 있으면 바꾸고 없으면 만든다. 유일 제약 위반이 이 트랜잭션 안에서 드러나도록 곧바로 flush 한다. */
    private LoopSettingView save(Long userId, Long agentId, boolean enabled, Instant snooze, Instant now) {
        ProactiveLoopSetting row = settings.findByUserIdAndAgentId(userId, agentId)
                .map(existing -> {
                    existing.change(enabled, snooze, now);
                    return existing;
                })
                .orElseGet(() -> ProactiveLoopSetting.of(userId, agentId, enabled, snooze, now));
        return view(settings.saveAndFlush(row), now);
    }

    private LoopSettingView view(ProactiveLoopSetting row, Instant now) {
        boolean available = properties.current().enabled();
        if (row == null) {
            return new LoopSettingView(available, false, null);
        }
        return new LoopSettingView(available, row.enabled(), row.snoozedAt(now) ? row.snoozedUntil() : null);
    }
}
