package com.bifos.assistant.browser.application;

import com.bifos.assistant.browser.domain.BrowserRuntime;
import com.bifos.assistant.browser.domain.RuntimeContainer;
import com.bifos.assistant.browser.domain.UserBrowser;
import com.bifos.assistant.browser.domain.type.UserBrowserStatus;
import com.bifos.assistant.browser.infra.UserBrowserRepository;
import com.bifos.assistant.shared.auth.UserAccessPolicy;
import com.bifos.assistant.shared.auth.UserAccessRevoked;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 쓰지 않는 브라우저를 멈추고 표와 실제 컨테이너를 맞춘다. 규칙은 {@code backend/docs/flow.md} 의 「상태 전이」 가 갖는다.
 *
 * <p>{@code assistant.browser.sweep-interval} 마다 돌고 기동 때 한 번 돈다. 기능이 꺼져 있으면 아무것도 하지 않는다. 한 줄이나 한
 * 컨테이너의 실패는 로그만 남기고 다음으로 넘어간다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserBrowserSweeper {

    /** {@code STARTING} 과 {@code STOPPING} 이 이보다 오래 그대로면 그 전이가 끝나지 못한 것으로 본다. 켜기 기다림 30초보다 넉넉하다. */
    static final Duration STUCK_AFTER = Duration.ofMinutes(2);

    private final UserBrowserService service;
    private final BrowserScreens screens;
    private final UserBrowserRepository browsers;
    private final BrowserRuntime runtime;
    private final BrowserUsage usage;
    private final UserAccessPolicy access;
    private final Clock clock;

    @Scheduled(
            fixedDelayString = "${assistant.browser.sweep-interval}",
            initialDelayString = "${assistant.browser.sweep-interval}")
    public void runScheduled() {
        sweep();
    }

    /** 재기동하면 {@code STARTING} 과 {@code STOPPING} 을 실제 컨테이너를 보고 정한다. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        sweep();
    }

    /** 자동 중지와 꺼진 사용자의 브라우저 멈춤, 상태 맞추기를 한 번 돈다. 예외는 로그만 남겨 기동과 다음 점검을 막지 않는다. */
    public void sweep() {
        if (!service.enabled()) {
            return;
        }
        try {
            stopIdle();
            stopRevoked();
            retryFailedStops();
            reconcile();
        } catch (RuntimeException ex) {
            log.warn("user browser sweep failed error={}", name(ex));
        }
    }

    /**
     * 사용자를 끄면 그 사용자의 브라우저를 멈춘다. 프로필은 남긴다(ADR 의 「삭제」).
     *
     * <p>끈 요청이 커밋된 뒤에 돈다. 그 자리에서는 커밋이 끝난 트랜잭션이 아직 묶여 있어, 저장소 호출이 거기에 붙으면 저장하지 못한다.
     * {@code NOT_SUPPORTED} 로 그 트랜잭션을 떼어 두면 서비스의 저장마다 저장소가 자기 트랜잭션을 연다. proxy 호출을 트랜잭션 밖에서 하는
     * 서비스의 규칙과도 맞다.
     *
     * <p>끄기 요청 스레드에서 동기로 돈다. 저장소에 비동기 실행기 설정이 없고, 가족 규모라 멈출 브라우저는 많아야 하나다. 멈추기는 proxy 의
     * 읽기 시간 제한(30초)까지 기다릴 수 있다.
     *
     * <p>{@code STARTING} 이나 {@code STOPPING} 이라 {@code BROWSER_BUSY} 이거나 멈추지 못하면 로그만 남긴다. 다음 점검의
     * {@link #stopRevoked()} 가 그 사용자를 다시 보고 멈춘다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void onAccessRevoked(UserAccessRevoked event) {
        if (!service.enabled()) {
            return;
        }
        try {
            service.get(event.userId())
                    .filter(browser -> browser.status() != UserBrowserStatus.STOPPED)
                    .ifPresent(browser -> service.stopById(browser.id()));
        } catch (RuntimeException ex) {
            log.warn("user browser stop on access removal failed userId={} error={}", event.userId(), name(ex));
        }
    }

    /** 유휴 시간이 지났고 화면도 중계 연결도 없는 {@code RUNNING} 을 멈춘다. 멈추기 직전에 줄을 다시 읽어 아직 유휴인지 본다. */
    void stopIdle() {
        Instant cutoff = clock.instant().minus(service.idleTimeout());
        for (UserBrowser browser : browsers.findByStatusAndLastActiveAtBefore(UserBrowserStatus.RUNNING, cutoff)) {
            if (usage.inUse(browser.id())) {
                continue;
            }
            try {
                if (service.stopIfIdle(browser.id(), cutoff)) {
                    log.info("user browser stopped after idle id={}", browser.id());
                }
            } catch (RuntimeException ex) {
                log.warn("user browser idle stop failed id={} error={}", browser.id(), name(ex));
            }
        }
    }

    /**
     * 허용 목록에서 꺼진 사용자의 {@code RUNNING} 과 {@code FAILED} 를 멈춘다. 사용자 끄기 때 멈추지 못한 브라우저를 여기서 다시 본다.
     *
     * <p>{@code STARTING} 과 {@code STOPPING} 은 건너뛴다. 켜기가 끝나면 다음 점검이 멈추고, 끝나지 못하면 상태 맞추기가 정한다.
     */
    void stopRevoked() {
        for (UserBrowser browser :
                browsers.findByStatusIn(List.of(UserBrowserStatus.RUNNING, UserBrowserStatus.FAILED))) {
            try {
                if (!access.allowed(browser.userId())) {
                    service.stopById(browser.id());
                    log.info("user browser stopped after access removal id={}", browser.id());
                }
            } catch (RuntimeException ex) {
                log.warn("user browser stop for removed user failed id={} error={}", browser.id(), name(ex));
            }
        }
    }

    /** 끄다 실패해 컨테이너가 남은 {@code FAILED} 를 다시 끈다. 남은 컨테이너가 동시 수와 메모리를 쥐고 있지 않게 한다. */
    void retryFailedStops() {
        for (UserBrowser browser : browsers.findByStatusAndContainerIdIsNotNull(UserBrowserStatus.FAILED)) {
            try {
                service.stopById(browser.id());
                log.info("user browser leftover container stopped id={}", browser.id());
            } catch (RuntimeException ex) {
                log.warn("user browser leftover stop failed id={} error={}", browser.id(), name(ex));
            }
        }
    }

    /** 실제 컨테이너와 표를 견준다. 목록을 먼저 읽고 줄을 읽어, 목록 뒤에 켜진 줄을 사라진 것으로 보지 않는다. */
    void reconcile() {
        Instant listedAt = clock.instant();
        List<RuntimeContainer> containers;
        try {
            containers = runtime.list();
        } catch (RuntimeException ex) {
            log.warn("user browser reconcile skipped: cannot list containers error={}", name(ex));
            return;
        }
        Map<String, UserBrowser> byKey = browsers.findAllByOrderByIdAsc().stream()
                .collect(Collectors.toMap(UserBrowser::profileKey, Function.identity()));
        for (UserBrowser browser : byKey.values()) {
            reconcileRow(browser, containers, listedAt);
        }
        for (RuntimeContainer container : containers) {
            if (orphan(container, Optional.ofNullable(container.profileKey()).map(byKey::get))) {
                removeQuietly(container.id());
            }
        }
    }

    private void reconcileRow(UserBrowser browser, List<RuntimeContainer> containers, Instant listedAt) {
        try {
            List<RuntimeContainer> own = containers.stream()
                    .filter(container -> browser.profileKey().equals(container.profileKey()))
                    .toList();
            boolean changedAfterListing = !browser.updatedAt().isBefore(listedAt);
            switch (browser.status()) {
                case RUNNING -> {
                    boolean alive = own.stream()
                            .anyMatch(container ->
                                    container.running() && container.id().equals(browser.containerId()));
                    if (!alive && !changedAfterListing) {
                        own.forEach(container -> runtime.remove(container.id()));
                        reset(browser, "container gone");
                    }
                }
                case STARTING, STOPPING -> {
                    if (browser.updatedAt().isBefore(listedAt.minus(STUCK_AFTER))) {
                        own.forEach(container -> runtime.remove(container.id()));
                        reset(browser, "transition stuck");
                    }
                }
                default -> {
                    // STOPPED 와 FAILED 의 남은 컨테이너는 아래 컨테이너 쪽에서 지운다
                }
            }
        } catch (RuntimeException ex) {
            log.warn("user browser reconcile failed id={} error={}", browser.id(), name(ex));
        }
    }

    private void reset(UserBrowser browser, String reason) {
        screens.close(browser.id());
        browser.resetStopped(clock.instant());
        try {
            browsers.saveAndFlush(browser);
            log.info("user browser reset to stopped id={} reason={}", browser.id(), reason);
        } catch (OptimisticLockingFailureException ex) {
            log.debug("user browser reconcile skipped id={}: changed concurrently", browser.id());
        }
    }

    /** 줄이 없거나, 멈춘 줄이거나, 켜진 줄과 실패한 줄이 가리키지 않는 컨테이너다. 전이 중인 줄의 컨테이너는 두지 않는다. */
    private static boolean orphan(RuntimeContainer container, Optional<UserBrowser> row) {
        if (row.isEmpty()) {
            return true;
        }
        UserBrowser browser = row.get();
        return switch (browser.status()) {
            case STOPPED -> true;
            case RUNNING, FAILED -> !container.id().equals(browser.containerId());
            case STARTING, STOPPING -> false;
        };
    }

    private void removeQuietly(String containerId) {
        try {
            runtime.remove(containerId);
            log.info("orphan browser container removed");
        } catch (RuntimeException ex) {
            log.warn("orphan browser container removal failed error={}", name(ex));
        }
    }

    private static String name(RuntimeException ex) {
        return ex.getClass().getSimpleName();
    }
}
