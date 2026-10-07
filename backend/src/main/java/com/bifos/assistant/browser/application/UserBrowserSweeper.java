package com.bifos.assistant.browser.application;

import com.bifos.assistant.browser.domain.BrowserRuntime;
import com.bifos.assistant.browser.domain.RuntimeContainer;
import com.bifos.assistant.browser.domain.UserBrowser;
import com.bifos.assistant.browser.domain.type.UserBrowserStatus;
import com.bifos.assistant.browser.infra.UserBrowserRepository;
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
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 쓰지 않는 브라우저를 멈추고 표와 실제 컨테이너를 맞춘다. 규칙은 {@code docs/backend/user-browser.md} 의 「상태 전이」 가 갖는다.
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
    private final UserBrowserRepository browsers;
    private final BrowserRuntime runtime;
    private final BrowserUsage usage;
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

    /** 자동 중지와 상태 맞추기를 한 번 돈다. */
    public void sweep() {
        if (!service.enabled()) {
            return;
        }
        stopIdle();
        reconcile();
    }

    /**
     * 사용자를 끄면 그 사용자의 브라우저를 멈춘다. 프로필은 남긴다(ADR 의 「삭제」).
     *
     * <p>끈 요청이 커밋된 뒤에 돈다. 멈추지 못하면 로그만 남기고, 그 사용자는 다시 로그인하지 못하므로 자동 중지가 멈춘다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
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

    /** 유휴 시간이 지났고 화면도 중계 연결도 없는 {@code RUNNING} 을 멈춘다. */
    void stopIdle() {
        Instant cutoff = clock.instant().minus(service.idleTimeout());
        for (UserBrowser browser : browsers.findByStatusAndLastActiveAtBefore(UserBrowserStatus.RUNNING, cutoff)) {
            if (usage.inUse(browser.id())) {
                continue;
            }
            try {
                service.stopById(browser.id());
                log.info("user browser stopped after idle id={}", browser.id());
            } catch (RuntimeException ex) {
                log.warn("user browser idle stop failed id={} error={}", browser.id(), name(ex));
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
