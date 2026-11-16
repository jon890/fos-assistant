package com.bifos.assistant.browser.application;

import com.bifos.assistant.browser.application.port.BrowserApprovedRuns;
import com.bifos.assistant.browser.application.port.BrowserUsageHandle;
import com.bifos.assistant.browser.domain.UserBrowser;
import com.bifos.assistant.browser.domain.type.UserBrowserStatus;
import com.bifos.assistant.browser.infra.UserBrowserRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사람이 「직접 할게요」 로 브라우저를 쥐고 「돌려주기」 로 놓는 잠금이다. 규칙은 {@code docs/features/browser-cowork/design.md} 의 「PR3
 * 사람 잠금」 과 ADR-20261116 / browser-lock 이 갖는다.
 *
 * <p>잠금은 {@code user_browser.human_held_at} 이고 조건부 갱신으로만 쓴다. 쥔 동안 중계가 에이전트를 거절하도록 열린 중계 연결을
 * 등록받아 회수하는 순간 닫는다. 갱신은 연결을 닫기 전에 커밋한다. 회수한 대화는 알림을 고를 때만 쓰므로 메모리에 둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrowserHumanHold {

    private final UserBrowserRepository browsers;
    private final BrowserApprovedRuns approvedRuns;
    private final BrowserAgentTabs agentTabs;
    private final Clock clock;
    private final PlatformTransactionManager transactions;
    /** 브라우저마다 열린 중계 연결을 닫는 함수다. */
    private final Map<Long, Set<Runnable>> relays = new ConcurrentHashMap<>();
    /** 사용자마다 회수한 대화의 공개 번호다. 대화 밖에서 회수했으면 없다. */
    private final Map<Long, String> origins = new ConcurrentHashMap<>();


    /**
     * 사람이 브라우저를 쥔다. 이미 쥐었으면 그대로 성공이다.
     *
     * @param origin 대화 패널에서 회수했으면 그 대화의 공개 번호, 아니면 {@code null}
     * @throws ApiException 브라우저가 없으면 {@code BROWSER_NOT_FOUND}, 켜져 있지 않으면 {@code BROWSER_BUSY}, 브라우저를 쓰는 승인한 실행이
     * 도는 중이면 {@code BROWSER_APPROVAL_RUNNING}
     */
    public void takeOver(Long userId, String origin) {
        UserBrowser browser = owned(userId);
        if (heldNow(browser)) {
            return;
        }
        requireNoApprovedRun(userId);
        if (browser.humanHeldAt() != null) {
            // 끄기가 실패해 남은 지난 잠금이다. 비운 뒤 새로 쥔다
            write(() -> browsers.releaseHumanHold(browser.id()));
        }
        if (write(() -> browsers.holdByHuman(userId, UserBrowserStatus.RUNNING, clock.instant())) == 0) {
            if (heldNow(owned(userId))) {
                return;
            }
            throw new ApiException(ErrorCode.BROWSER_BUSY, "user browser is not running");
        }
        // 잠그는 사이 승인이 실행을 시작했으면 승인 쪽을 살린다(설계의 「회수와 승인의 경쟁」)
        if (approvedRuns.running(userId)) {
            write(() -> browsers.releaseHumanHold(browser.id()));
            throw approvalRunning();
        }
        if (origin != null) {
            origins.put(userId, origin);
        } else {
            origins.remove(userId);
        }
        agentTabs.forget(browser.id());
        kick(browser.id());
        log.info("user browser taken over by human id={}", browser.id());
    }


    /** 사람이 쥔 것을 놓는다. 쥐지 않았거나 브라우저가 없어도 성공이다. */
    public void giveBack(Long userId) {
        browsers.findByUserId(userId).ifPresent(browser -> release(browser.id(), userId));
    }


    /** 끄기와 점검이 그 브라우저의 잠금을 푼다. */
    public void release(Long browserId, Long userId) {
        origins.remove(userId);
        if (write(() -> browsers.releaseHumanHold(browserId)) > 0) {
            log.info("user browser human hold released id={}", browserId);
        }
    }


    /** 사람이 그 사용자의 브라우저를 쥐고 있는가. */
    public boolean held(Long userId) {
        return browsers.findByUserId(userId).map(BrowserHumanHold::heldNow).orElse(false);
    }


    /** 사람이 쥐고 있으면 회수한 대화의 공개 번호다. 대화 밖에서 회수했거나 Control Plane 이 다시 떴으면 비어 있다. */
    public Optional<String> heldFrom(Long userId) {
        return Optional.ofNullable(origins.get(userId));
    }


    /**
     * 화면의 {@code hold} 사건 본문이다. 승인한 실행은 지금 탭들에 쥔 탭이 있을 때만 묻는다.
     *
     * @param agentTabs 지금 탭들에 에이전트가 쥔 탭이 있는가
     */
    public Map<String, Object> view(Long userId, boolean agentTabs) {
        Map<String, Object> view = new LinkedHashMap<>();
        boolean human = held(userId);
        view.put("human", human);
        view.put("approved", !human && agentTabs && approvedRuns.running(userId));
        return view;
    }


    /**
     * 열린 중계 연결을 등록한다. 회수하면 {@code close} 를 부른다. 돌려받은 핸들을 닫으면 등록을 뺀다.
     */
    public BrowserUsageHandle watch(Long browserId, Runnable close) {
        relays.computeIfAbsent(browserId, id -> ConcurrentHashMap.newKeySet()).add(close);
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                relays.computeIfPresent(browserId, (id, set) -> {
                    set.remove(close);
                    return set.isEmpty() ? null : set;
                });
            }
        };
    }


    private void kick(Long browserId) {
        Set<Runnable> open = relays.get(browserId);
        if (open == null) {
            return;
        }
        for (Runnable close : List.copyOf(open)) {
            try {
                close.run();
            } catch (RuntimeException ex) {
                log.debug("browser relay close failed id={} error={}", browserId, ex.getClass().getSimpleName());
            }
        }
    }


    /** 조건부 갱신 하나를 짧은 트랜잭션으로 커밋한다. 회수가 연결을 닫기 전에 잠금이 보여야 다시 붙는 연결을 거절한다. */
    private int write(Supplier<Integer> update) {
        Integer changed = new TransactionTemplate(transactions).execute(status -> update.get());
        return changed == null ? 0 : changed;
    }


    private void requireNoApprovedRun(Long userId) {
        if (approvedRuns.running(userId)) {
            throw approvalRunning();
        }
    }


    private static ApiException approvalRunning() {
        return new ApiException(ErrorCode.BROWSER_APPROVAL_RUNNING, "an approved browser run is in progress");
    }


    private static boolean heldNow(UserBrowser browser) {
        return browser.heldByHuman();
    }


    private UserBrowser owned(Long userId) {
        return browsers.findByUserId(userId)
            .orElseThrow(() -> new ApiException(ErrorCode.BROWSER_NOT_FOUND, "no user browser"));
    }
}
