package com.bifos.assistant.people.application;

import com.bifos.assistant.people.application.dto.UserPurgeOutcome;
import com.bifos.assistant.people.application.dto.UserPurgeStep;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.domain.NoAdminToTakeOverException;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 정리를 예약한 지 30일이 지난 사람의 데이터를 지운다(ADR-20261101 / user-purge).
 *
 * <p>그 사람의 사용자마다 {@link UserPurgeStep} 의 순서로 {@link UserPurgeWriter} 를 부른다. 단계마다 잠근 뒤 아직 꺼져 있고 예약이
 * 그대로인지 다시 본다. 대화 정리가 끝나지 않았으면 데이터 key 와 사용자 줄을 지우지 않고 다음 차례로 미룬다. 사용자가 모두 지워지면
 * 예약을 비운다. 허용 목록 줄은 꺼진 채 남는다.
 *
 * <p>어느 단계든 실패하면 다음 차례에 첫 단계부터 다시 한다. 단계마다 이미 지운 것을 다시 지워도 같은 결과다. 실패가 이어지는 사람은
 * 기다리는 간격을 한 시간까지 두 배씩 늘리고, 다섯 번 잇달아 실패하면 error 로그를 남긴다. 넘겨받을 관리자가 없을 때도 이 경로로
 * 알린다. 이 간격은 메모리에만 둔다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserPurger {

    /** 예약한 뒤 지우기 시작할 때까지 기다리는 기간이다. 이 안에 다시 켜면 아무것도 지우지 않는다. */
    public static final Duration GRACE = Duration.ofDays(30);

    /** 한 차례에 보는 사람 수다. 먼저 예약한 사람부터 본다. */
    private static final int BATCH = 10;

    /** 한 차례에 쓰는 시간의 상한이다. 정리는 다른 주기 작업과 scheduler 스레드 하나를 함께 쓴다. */
    private static final Duration TICK_BUDGET = Duration.ofSeconds(20);

    /** 이만큼 잇달아 실패하면 error 로그로 알린다. 운영자가 원인을 찾아야 하는 사람이다. */
    private static final int ALERT_ATTEMPTS = 5;

    private static final Duration FIRST_BACKOFF = Duration.ofMinutes(1);
    private static final Duration MAX_BACKOFF = Duration.ofHours(1);

    /** 기다리는 사람이 없을 때 후보 질의에 넘기는 번호다. 허용 목록 번호는 1부터다. */
    private static final List<Long> NONE_SKIPPED = List.of(-1L);

    private final AllowedPersonRepository people;
    private final AppUserRepository users;
    private final UserPurgeWriter writer;
    private final Clock clock;

    /** 실패한 사람을 다음에 다시 볼 시각과 그때 기다릴 간격, 잇단 실패 수다. */
    private final Map<Long, Backoff> backoffs = new ConcurrentHashMap<>();


    /** 운영은 매분 돈다. 검사에서는 {@code -} 로 끄고 {@link #purgeDue} 를 직접 부른다. */
    @Scheduled(cron = "${assistant.people.purge-cron}")
    public void runScheduled() {
        purgeDue(clock.instant());
    }


    /**
     * 예약한 지 30일이 지난 사람을 정리한다. 기다리는 간격 안의 사람은 후보에서 뺀다.
     *
     * @return 이번 차례에 정리를 마친 사람 수
     */
    public int purgeDue(Instant now) {
        Instant at = now.truncatedTo(ChronoUnit.MICROS);
        Instant cutoff = at.minus(GRACE);
        List<Long> waitingIds = backoffs.entrySet().stream()
            .filter(entry -> at.isBefore(entry.getValue().retryAt()))
            .map(Map.Entry::getKey)
            .toList();
        List<Long> candidates = people.findPurgeDue(
            cutoff, waitingIds.isEmpty() ? NONE_SKIPPED : waitingIds, PageRequest.of(0, BATCH));
        long started = System.nanoTime();
        int purged = 0;
        int failed = 0;
        for (Long personId : candidates) {
            if (Duration.ofNanos(System.nanoTime() - started).compareTo(TICK_BUDGET) > 0) {
                break;
            }
            try {
                if (purgeOne(personId, cutoff, at)) {
                    purged++;
                    backoffs.remove(personId);
                }
            } catch (RuntimeException ex) {
                failed++;
                recordFailure(personId, at, ex);
            }
        }
        if (purged > 0 || failed > 0) {
            log.info("예약한 사용자 정리 purged={} failed={}", purged, failed);
        }
        return purged;
    }


    /**
     * 한 사람을 정리한다.
     *
     * @return 사용자를 모두 지우고 예약을 비웠으면 참. 대화 정리를 기다리거나 그 사이 취소됐으면 거짓
     */
    private boolean purgeOne(Long personId, Instant cutoff, Instant now) {
        String email = people.findEmailById(personId).orElse(null);
        if (email == null) {
            return false;
        }
        List<Long> userIds = users.findAllByNormalizedEmail(AllowedPerson.normalizeEmail(email)).stream()
            .map(AppUser::id)
            .sorted()
            .toList();
        for (Long userId : userIds) {
            for (UserPurgeStep step : UserPurgeStep.values()) {
                if (writer.run(personId, userId, step, cutoff, now) != UserPurgeOutcome.DONE) {
                    return false;
                }
            }
        }
        boolean finished = writer.finish(personId, cutoff);
        if (finished) {
            log.info("예약한 사용자의 데이터를 모두 지웠다 personId={} users={}", personId, userIds.size());
        }
        return finished;
    }


    private void recordFailure(Long personId, Instant now, RuntimeException ex) {
        Backoff previous = backoffs.get(personId);
        Duration next = previous == null ? FIRST_BACKOFF : min(previous.interval().multipliedBy(2), MAX_BACKOFF);
        int attempts = previous == null ? 1 : previous.attempts() + 1;
        backoffs.put(personId, new Backoff(now.plus(next), next, attempts));
        // 예외 메시지에는 원격 응답이 섞일 수 있어 적지 않는다. 넘겨받을 관리자가 없는 것은 code 로 가른다.
        String code = ex instanceof ApiException api ? api.code().name()
            : ex instanceof NoAdminToTakeOverException ? "NO_ADMIN_TO_TAKE_OVER" : "-";
        if (attempts == ALERT_ATTEMPTS) {
            log.error(
                "예약한 사용자를 잇달아 정리하지 못했다 personId={} attempts={} error={} code={}",
                personId,
                attempts,
                ex.getClass().getSimpleName(),
                code);
        } else {
            log.warn(
                "예약한 사용자를 정리하지 못했다 personId={} attempts={} error={} code={}",
                personId,
                attempts,
                ex.getClass().getSimpleName(),
                code);
        }
    }


    private static Duration min(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }


    /** 실패한 사람을 다시 볼 시각과 그때 쓴 간격, 잇단 실패 수다. */
    private record Backoff(Instant retryAt, Duration interval, int attempts) {
    }
}
