package com.bifos.assistant.connector.application;

import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 원고 저장 job 의 기한 정리다. 원고 승인 줄은 일반 실행 정리에서 빠지므로, 끝나지 않은 job 과 그 승인 줄을 여기서 끝낸다.
 *
 * <p>예약을 커밋한 뒤 시작을 보내기 전에 Control Plane 이 내려가 DISPATCHED 로 남은 job 도 이 정리가 닫는다.
 */
@Component
@RequiredArgsConstructor
public class ContentDraftJobExpirer {
    /**
     * ticket 최대 수명({@link ContentDraftSaveTicket#MAX_LIFETIME}, 660초)과 worker 실행 상한(600초)보다 넉넉히 길다. 이 뒤에는 worker 가 claim
     * 을 더 받지 못하므로 저장 클릭이 새로 생기지 않는다.
     */
    static final Duration OVERDUE = Duration.ofMinutes(15);

    private final ContentDraftJobs jobs;
    private final Clock clock;


    /** 승인 줄 만료와 같은 주기로 돈다. 검사에서는 {@code -} 로 끄고 본체를 직접 부른다. */
    @Scheduled(cron = "${assistant.connector.policy.expire-cron}")
    public void runScheduled() {
        jobs.closeOverdue(clock.instant().minus(OVERDUE));
    }
}
