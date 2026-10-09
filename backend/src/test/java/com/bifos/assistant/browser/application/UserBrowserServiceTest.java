package com.bifos.assistant.browser.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.bifos.assistant.browser.application.model.BrowserEndpoint;
import com.bifos.assistant.browser.application.model.UserBrowserSnapshot;
import com.bifos.assistant.browser.domain.BrowserProfileStore;
import com.bifos.assistant.browser.domain.UserBrowser;
import com.bifos.assistant.browser.domain.type.UserBrowserStatus;
import com.bifos.assistant.browser.infra.BrowserProperties;
import com.bifos.assistant.browser.infra.UserBrowserRepository;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.FakeBrowserRuntime;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 켜기, 끄기, 지우기의 순서와 동시 수, 실패 처리를 실제 DB 와 대역 proxy 로 본다. 규칙은 {@code backend/docs/flow.md} 의
 * 「상태 전이」 다.
 */
@BackendIntegrationTest
class UserBrowserServiceTest {

    /** 대역 proxy 가 켜진 컨테이너에 주는 CDP 주소다. */
    private static final URI FAKE_CDP = URI.create("http://192.0.2.10:9999");

    @Autowired
    UserBrowserRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    private FakeBrowserRuntime runtime;
    private final Set<String> profileDirs = ConcurrentHashMap.newKeySet();
    private final List<String> profileCalls = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean cdpReady = new AtomicBoolean(true);
    /** 대역 프로필 저장소가 디렉터리를 만들기 전에 부른다. 시험이 바꿔 끼워 만들기 도중에 멈춘다. */
    private volatile Runnable beforeEnsure = () -> {};

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM user_browser");
        runtime = new FakeBrowserRuntime();
        profileDirs.clear();
        profileCalls.clear();
        cdpReady.set(true);
        beforeEnsure = () -> {};
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM user_browser");
    }

    @Test
    @DisplayName("만들면 프로필 디렉터리가 생기고 STOPPED 로 저장되며 두 번 만들면 BROWSER_EXISTS 다")
    void createsStoppedBrowserOnce() {
        UserBrowserService service = service(true, 2);

        UserBrowserSnapshot created = service.create(101L);

        assertThat(created.status()).isEqualTo(UserBrowserStatus.STOPPED);
        assertThat(profileDirs).containsExactly(UserBrowserService.profileKey(101L));
        assertThat(UserBrowserService.profileKey(101L)).matches("[0-9a-f]{64}");
        assertCode(() -> service.create(101L), ErrorCode.BROWSER_EXISTS);
    }

    @Test
    @DisplayName("켜면 컨테이너를 만들고 켜서 CDP 가 답한 뒤 RUNNING 이 된다")
    void startsBrowserUntilCdpAnswers() {
        UserBrowserService service = service(true, 2);
        service.create(101L);

        UserBrowserSnapshot started = service.start(101L);

        assertThat(started.status()).isEqualTo(UserBrowserStatus.RUNNING);
        assertThat(started.startedAt()).isNotNull();
        assertThat(runtime.containers()).hasSize(1);
        assertThat(runtime.containers().values().iterator().next().running()).isTrue();
        assertThat(repository.findByUserId(101L).orElseThrow().containerId()).isEqualTo("c1");
    }

    @Test
    @DisplayName("이미 켜진 브라우저를 켜면 컨테이너를 더 만들지 않고 그대로 돌려준다")
    void returnsRunningBrowserWithoutNewContainer() {
        UserBrowserService service = service(true, 2);
        service.create(101L);
        service.start(101L);

        UserBrowserSnapshot again = service.start(101L);

        assertThat(again.status()).isEqualTo(UserBrowserStatus.RUNNING);
        assertThat(runtime.containers()).hasSize(1);
    }

    @Test
    @DisplayName("동시 수가 차면 BROWSER_CAPACITY 로 거절하고 켜진 브라우저를 멈추지 않는다")
    void rejectsStartBeyondCapacity() {
        UserBrowserService service = service(true, 1);
        service.create(101L);
        service.create(102L);
        service.start(101L);

        assertCode(() -> service.start(102L), ErrorCode.BROWSER_CAPACITY);

        assertThat(repository.findByUserId(101L).orElseThrow().status()).isEqualTo(UserBrowserStatus.RUNNING);
        assertThat(repository.findByUserId(102L).orElseThrow().status()).isEqualTo(UserBrowserStatus.STOPPED);
    }

    @Test
    @DisplayName("CDP 가 정한 시간 안에 답하지 않으면 컨테이너를 지우고 FAILED 로 둔다")
    void failsAndRemovesContainerWhenCdpTimesOut() {
        UserBrowserService service = service(true, 2);
        service.create(101L);
        cdpReady.set(false);

        assertCode(() -> service.start(101L), ErrorCode.BROWSER_START_FAILED);

        var saved = repository.findByUserId(101L).orElseThrow();
        assertThat(saved.status()).isEqualTo(UserBrowserStatus.FAILED);
        assertThat(saved.lastError()).isEqualTo(UserBrowserService.START_TIMEOUT);
        assertThat(runtime.removed()).containsExactly("c1");
        assertThat(runtime.containers()).isEmpty();
    }

    @Test
    @DisplayName("켜는 도중 컨테이너가 끝나면 시간을 기다리지 않고 start_exited 로 두며 컨테이너를 지운다")
    void failsFastWhenContainerExits() {
        UserBrowserService service = service(true, 2);
        service.create(101L);
        runtime.exitOnStart(21);

        assertCode(() -> service.start(101L), ErrorCode.BROWSER_START_FAILED);

        var saved = repository.findByUserId(101L).orElseThrow();
        assertThat(saved.status()).isEqualTo(UserBrowserStatus.FAILED);
        assertThat(saved.lastError()).isEqualTo(UserBrowserService.START_EXITED);
        assertThat(runtime.removed()).containsExactly("c1");

        runtime.exitOnStart(null);
        assertThat(service.start(101L).status()).isEqualTo(UserBrowserStatus.RUNNING);
    }

    @Test
    @DisplayName("켜기 전에 같은 프로필 키의 남은 컨테이너를 지우고 다른 키의 컨테이너는 둔다")
    void removesLeftoverContainersOfSameProfileBeforeStart() {
        UserBrowserService service = service(true, 2);
        service.create(101L);
        runtime.plant("leftover", UserBrowserService.profileKey(101L), true);
        runtime.plant("other", UserBrowserService.profileKey(102L), true);

        assertThat(service.start(101L).status()).isEqualTo(UserBrowserStatus.RUNNING);

        assertThat(runtime.removed()).containsExactly("leftover");
        assertThat(runtime.containers()).containsOnlyKeys("other", "c1");
    }

    @Test
    @DisplayName("남은 컨테이너를 확인하지 못하면 새 컨테이너를 만들지 않고 start_failed 로 둔다")
    void doesNotCreateWhenLeftoversCannotBeChecked() {
        UserBrowserService service = service(true, 2);
        service.create(101L);
        runtime.failingActions().add("list");

        assertCode(() -> service.start(101L), ErrorCode.BROWSER_START_FAILED);

        assertThat(repository.findByUserId(101L).orElseThrow().lastError()).isEqualTo(UserBrowserService.START_FAILED);
        assertThat(runtime.containers()).isEmpty();
    }

    @Test
    @DisplayName("남은 컨테이너를 지우지 못하면 새 컨테이너를 만들지 않고 start_failed 로 둔다")
    void doesNotCreateWhenLeftoverCannotBeRemoved() {
        UserBrowserService service = service(true, 2);
        service.create(101L);
        runtime.plant("leftover", UserBrowserService.profileKey(101L), true);
        runtime.failingActions().add("remove");

        assertCode(() -> service.start(101L), ErrorCode.BROWSER_START_FAILED);

        assertThat(repository.findByUserId(101L).orElseThrow().lastError()).isEqualTo(UserBrowserService.START_FAILED);
        assertThat(runtime.containers()).containsOnlyKeys("leftover");
    }

    @Test
    @DisplayName("켜진 브라우저를 다시 켜면 남은 컨테이너 정리가 돌지 않아 켜진 컨테이너를 지우지 않는다")
    void startingRunningBrowserDoesNotRemoveItsContainer() {
        UserBrowserService service = service(true, 2);
        service.create(101L);
        service.start(101L);

        service.start(101L);

        assertThat(runtime.removed()).isEmpty();
        assertThat(runtime.containers()).containsOnlyKeys("c1");
    }

    @Test
    @DisplayName("proxy 가 켜기를 거절하면 만든 컨테이너를 지우고 start_failed 로 둔 뒤 다시 켤 수 있다")
    void failsOnProxyErrorAndAllowsRetry() {
        UserBrowserService service = service(true, 2);
        service.create(101L);
        runtime.failingActions().add("start");

        assertCode(() -> service.start(101L), ErrorCode.BROWSER_START_FAILED);
        assertThat(repository.findByUserId(101L).orElseThrow().lastError()).isEqualTo(UserBrowserService.START_FAILED);
        assertThat(runtime.containers()).isEmpty();

        runtime.failingActions().clear();
        assertThat(service.start(101L).status()).isEqualTo(UserBrowserStatus.RUNNING);
    }

    @Test
    @DisplayName("끄면 컨테이너를 멈추고 지운 뒤 STOPPED 가 되고 멈춘 브라우저를 다시 꺼도 그대로다")
    void stopsAndRemovesContainer() {
        UserBrowserService service = service(true, 2);
        service.create(101L);
        service.start(101L);

        UserBrowserSnapshot stopped = service.stop(101L);

        assertThat(stopped.status()).isEqualTo(UserBrowserStatus.STOPPED);
        assertThat(runtime.containers()).isEmpty();
        assertThat(repository.findByUserId(101L).orElseThrow().containerId()).isNull();
        assertThat(service.stop(101L).status()).isEqualTo(UserBrowserStatus.STOPPED);
    }

    @Test
    @DisplayName("proxy 가 끄기를 거절하면 stop_failed 로 두고 BROWSER_STOP_FAILED 이며 다시 끌 수 있다")
    void failsStopOnProxyErrorAndAllowsRetry() {
        UserBrowserService service = service(true, 2);
        service.create(101L);
        service.start(101L);
        runtime.failingActions().add("stop");

        assertCode(() -> service.stop(101L), ErrorCode.BROWSER_STOP_FAILED);
        assertThat(repository.findByUserId(101L).orElseThrow().lastError()).isEqualTo(UserBrowserService.STOP_FAILED);

        runtime.failingActions().clear();
        assertThat(service.stop(101L).status()).isEqualTo(UserBrowserStatus.STOPPED);
    }

    @Test
    @DisplayName("끄다 실패해 컨테이너가 남은 브라우저도 동시 수에 든다")
    void countsFailedBrowserWithContainerTowardCapacity() {
        UserBrowserService service = service(true, 1);
        service.create(101L);
        service.create(102L);
        service.start(101L);
        runtime.failingActions().add("stop");
        assertCode(() -> service.stop(101L), ErrorCode.BROWSER_STOP_FAILED);
        runtime.failingActions().clear();

        assertCode(() -> service.start(102L), ErrorCode.BROWSER_CAPACITY);
    }

    @Test
    @DisplayName("끄다 실패해 남은 컨테이너를 켜기가 지우면 동시 수 1 에서도 다시 켤 수 있다")
    void restartsAfterLeftoverRemovalWithSingleSlot() {
        UserBrowserService service = service(true, 1);
        service.create(101L);
        service.start(101L);
        runtime.failingActions().add("stop");
        assertCode(() -> service.stop(101L), ErrorCode.BROWSER_STOP_FAILED);
        runtime.failingActions().clear();

        assertThat(service.start(101L).status()).isEqualTo(UserBrowserStatus.RUNNING);
    }

    @Test
    @DisplayName("만들 때 지우다 만 프로필 디렉터리를 먼저 비운다")
    void clearsLeftoverProfileOnCreate() {
        UserBrowserService service = service(true, 2);

        service.create(101L);

        assertThat(profileCalls).containsExactly("delete", "ensure");
    }

    @Test
    @DisplayName("지우면 끈 뒤 프로필 디렉터리와 줄을 지운다")
    void deletesBrowserWithProfile() {
        UserBrowserService service = service(true, 2);
        service.create(101L);
        service.start(101L);

        service.delete(101L);

        assertThat(repository.findByUserId(101L)).isEmpty();
        assertThat(profileDirs).isEmpty();
        assertThat(runtime.containers()).isEmpty();
    }

    @Test
    @DisplayName("끄다 실패해 컨테이너가 남은 줄을 켜면 남은 컨테이너를 먼저 지우고 새로 띄운다")
    void removesLeftoverContainerBeforeRestart() {
        UserBrowserService service = service(true, 2);
        service.create(101L);
        service.start(101L);
        runtime.failingActions().add("stop");
        assertCode(() -> service.stop(101L), ErrorCode.BROWSER_STOP_FAILED);
        assertThat(repository.findByUserId(101L).orElseThrow().containerId()).isEqualTo("c1");
        runtime.failingActions().clear();

        assertThat(service.start(101L).status()).isEqualTo(UserBrowserStatus.RUNNING);

        assertThat(runtime.removed()).containsExactly("c1");
        assertThat(runtime.containers()).containsOnlyKeys("c2");
    }

    @Test
    @DisplayName("남은 컨테이너를 지우지 못하면 BROWSER_STOP_FAILED 로 거절하고 번호를 남긴다")
    void rejectsRestartWhenLeftoverRemovalFails() {
        UserBrowserService service = service(true, 2);
        service.create(101L);
        service.start(101L);
        runtime.failingActions().add("stop");
        assertCode(() -> service.stop(101L), ErrorCode.BROWSER_STOP_FAILED);
        runtime.failingActions().clear();
        runtime.failingActions().add("remove");

        assertCode(() -> service.start(101L), ErrorCode.BROWSER_STOP_FAILED);

        var saved = repository.findByUserId(101L).orElseThrow();
        assertThat(saved.status()).isEqualTo(UserBrowserStatus.FAILED);
        assertThat(saved.containerId()).isEqualTo("c1");
        assertThat(runtime.containers()).containsOnlyKeys("c1");
    }

    @Test
    @DisplayName("지우는 사이에 다른 전이가 줄을 바꾸면 BROWSER_BUSY 이고 줄과 프로필이 남는다")
    void keepsRowAndProfileWhenChangedDuringDelete() {
        UserBrowserRepository racing = mock(UserBrowserRepository.class, delegatesTo(repository));
        doAnswer(call -> {
                    jdbc.update(
                            "UPDATE user_browser SET status = 'STARTING', version = version + 1 WHERE user_id = 101");
                    repository.delete(call.getArgument(0));
                    return null;
                })
                .when(racing)
                .delete(any());
        UserBrowserService service = service(true, 2, racing);
        service.create(101L);

        assertCode(() -> service.delete(101L), ErrorCode.BROWSER_BUSY);

        assertThat(repository.findByUserId(101L).orElseThrow().status()).isEqualTo(UserBrowserStatus.STARTING);
        assertThat(profileDirs).containsExactly(UserBrowserService.profileKey(101L));
    }

    @Test
    @DisplayName("관리자는 번호로 끄고 지운다")
    void stopsAndDeletesById() {
        UserBrowserService service = service(true, 2);
        Long id = service.create(101L).id();
        service.start(101L);

        assertThat(service.stopById(id).status()).isEqualTo(UserBrowserStatus.STOPPED);
        service.deleteById(id);

        assertThat(repository.findById(id)).isEmpty();
        assertCode(() -> service.stopById(id), ErrorCode.BROWSER_NOT_FOUND);
    }

    @Test
    @DisplayName("없는 브라우저를 켜면 BROWSER_NOT_FOUND 다")
    void rejectsStartWithoutBrowser() {
        assertCode(() -> service(true, 2).start(999L), ErrorCode.BROWSER_NOT_FOUND);
    }

    @Test
    @DisplayName("기능이 꺼져 있으면 모든 쓰기가 BROWSER_DISABLED 이고 조회만 된다")
    void rejectsWritesWhenDisabled() {
        UserBrowserService service = service(false, 2);

        assertCode(() -> service.create(101L), ErrorCode.BROWSER_DISABLED);
        assertCode(() -> service.start(101L), ErrorCode.BROWSER_DISABLED);
        assertCode(() -> service.stop(101L), ErrorCode.BROWSER_DISABLED);
        assertCode(() -> service.delete(101L), ErrorCode.BROWSER_DISABLED);
        assertCode(() -> service.stopById(1L), ErrorCode.BROWSER_DISABLED);
        assertCode(() -> service.deleteById(1L), ErrorCode.BROWSER_DISABLED);
        assertThat(service.get(101L)).isEmpty();
        assertThat(runtime.containers()).isEmpty();
    }

    @Test
    @DisplayName("켜진 브라우저의 활동 시각은 1분에 한 번까지만 바뀐다")
    void touchesRunningBrowserOncePerMinute() {
        UserBrowserService service = service(true, 2);
        service.create(101L);
        service.start(101L);
        // DB 는 나노초를 마이크로초로 반올림해 남기므로 메모리의 시각 대신 저장된 값끼리 견준다
        var stored = repository.findByUserId(101L).orElseThrow().lastActiveAt();

        service.touch(101L);

        assertThat(repository.findByUserId(101L).orElseThrow().lastActiveAt()).isEqualTo(stored);
    }

    @Test
    @DisplayName("마지막 기록에서 1분이 지나지 않은 활동은 DB 를 읽지 않고 거른다")
    void skipsDatabaseWithinTouchInterval() {
        service(true, 2).create(101L);
        Long id = service(true, 2).start(101L).id();
        jdbc.update(
                "UPDATE user_browser SET last_active_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(2))),
                id);
        UserBrowserRepository counting = mock(UserBrowserRepository.class, delegatesTo(repository));
        UserBrowserService service = service(true, 2, counting);

        service.touch(101L);
        service.touch(101L);
        service.touch(101L);

        verify(counting, times(1)).findByUserId(101L);
        verify(counting, times(1)).save(any());
    }

    @Test
    @DisplayName("중계가 켜 둘 때 브라우저가 없으면 만들고 켠 뒤 그 번호와 CDP 주소를 준다")
    void ensureRunningCreatesAndStartsMissingBrowser() {
        UserBrowserService service = service(true, 2);

        BrowserEndpoint endpoint = service.ensureRunning(101L);

        var saved = repository.findByUserId(101L).orElseThrow();
        assertThat(saved.status()).isEqualTo(UserBrowserStatus.RUNNING);
        assertThat(endpoint).isEqualTo(new BrowserEndpoint(saved.id(), FAKE_CDP));
        assertThat(profileDirs).containsExactly(UserBrowserService.profileKey(101L));
    }

    @Test
    @DisplayName("다른 전이가 켜는 중이면 기다렸다가 RUNNING 이 되면 그 컨테이너의 주소를 준다")
    void ensureRunningWaitsForConcurrentStart() {
        UserBrowserService service = service(true, 2);
        Long id = service.create(101L).id();
        markStatus(101L, "STARTING", null);
        service.sleeper = pause -> {
            runtime.plant("other-start", UserBrowserService.profileKey(101L), true);
            markStatus(101L, "RUNNING", "other-start");
        };

        BrowserEndpoint endpoint = service.ensureRunning(101L);

        assertThat(endpoint).isEqualTo(new BrowserEndpoint(id, FAKE_CDP));
        assertThat(repository.findByUserId(101L).orElseThrow().containerId()).isEqualTo("other-start");
        assertThat(runtime.containers()).containsOnlyKeys("other-start");
    }

    @Test
    @DisplayName("기다리는 사이 다른 전이가 STOPPED 로 끝나면 다시 켠다")
    void ensureRunningStartsAgainAfterConcurrentStop() {
        UserBrowserService service = service(true, 2);
        service.create(101L);
        markStatus(101L, "STOPPING", null);
        service.sleeper = pause -> markStatus(101L, "STOPPED", null);

        BrowserEndpoint endpoint = service.ensureRunning(101L);

        assertThat(endpoint.cdp()).isEqualTo(FAKE_CDP);
        assertThat(repository.findByUserId(101L).orElseThrow().status()).isEqualTo(UserBrowserStatus.RUNNING);
        assertThat(repository.findByUserId(101L).orElseThrow().containerId()).isEqualTo("c1");
    }

    @Test
    @DisplayName("자기 줄이 켜는 중인 채로 시간이 지나면 동시 수가 차 있어도 BROWSER_CAPACITY 가 아니라 BROWSER_BUSY 다")
    void ensureRunningReportsBusyWhenOwnStartNeverFinishes() {
        UserBrowserService service = service(true, 1);
        service.create(101L);
        markStatus(101L, "STARTING", null);

        assertCode(() -> service.ensureRunning(101L), ErrorCode.BROWSER_BUSY);

        assertThat(repository.findByUserId(101L).orElseThrow().status()).isEqualTo(UserBrowserStatus.STARTING);
        assertThat(runtime.containers()).isEmpty();
    }

    @Test
    @DisplayName("다른 브라우저로 동시 수가 차면 BROWSER_CAPACITY 를 그대로 던지고 켜진 브라우저를 멈추지 않는다")
    void ensureRunningPropagatesCapacity() {
        UserBrowserService service = service(true, 1);
        service.create(101L);
        service.start(101L);

        assertCode(() -> service.ensureRunning(102L), ErrorCode.BROWSER_CAPACITY);

        assertThat(repository.findByUserId(101L).orElseThrow().status()).isEqualTo(UserBrowserStatus.RUNNING);
        assertThat(repository.findByUserId(102L).orElseThrow().status()).isEqualTo(UserBrowserStatus.STOPPED);
    }

    @Test
    @DisplayName("자기 줄이 켜는 중이면 동시 수가 차 있어도 켜기는 BROWSER_CAPACITY 가 아니라 BROWSER_BUSY 다")
    void startReportsBusyBeforeCountingWhenOwnRowIsChanging() {
        UserBrowserService service = service(true, 1);
        service.create(101L);
        markStatus(101L, "STARTING", null);

        assertCode(() -> service.start(101L), ErrorCode.BROWSER_BUSY);

        assertThat(repository.findByUserId(101L).orElseThrow().status()).isEqualTo(UserBrowserStatus.STARTING);
        assertThat(runtime.containers()).isEmpty();
    }

    @Test
    @DisplayName("STOPPED 를 읽은 뒤 같은 사용자의 다른 요청이 먼저 켜기 시작하면 진 쪽은 BROWSER_CAPACITY 없이 기다려 그 주소를 받는다")
    void ensureRunningWaitsWhenAnotherRequestStartsAfterRead() {
        UserBrowserRepository racing = mock(UserBrowserRepository.class, delegatesTo(repository));
        AtomicBoolean raced = new AtomicBoolean(false);
        // 처음 STOPPED 를 읽은 직후 다른 요청이 잠금을 먼저 잡아 STARTING 으로 저장한 것처럼 바꾼다. 돌려주는 줄은 읽은 그대로다.
        // 줄이 없는 채로 시작해 그 첫 읽기가 기다리기의 읽기가 되게 한다
        doAnswer(call -> {
                    Optional<UserBrowser> read = repository.findByUserId(call.getArgument(0));
                    boolean stopped = read.map(browser -> browser.status() == UserBrowserStatus.STOPPED)
                            .orElse(false);
                    if (stopped && raced.compareAndSet(false, true)) {
                        markStatus(101L, "STARTING", null);
                    }
                    return read;
                })
                .when(racing)
                .findByUserId(any());
        UserBrowserService service = service(true, 1, racing);
        service.sleeper = pause -> {
            runtime.plant("other-start", UserBrowserService.profileKey(101L), true);
            markStatus(101L, "RUNNING", "other-start");
        };

        BrowserEndpoint endpoint = service.ensureRunning(101L);

        var saved = repository.findByUserId(101L).orElseThrow();
        assertThat(raced).as("다른 요청이 끼어든 시점을 지났는지").isTrue();
        assertThat(endpoint).isEqualTo(new BrowserEndpoint(saved.id(), FAKE_CDP));
        assertThat(saved.containerId()).isEqualTo("other-start");
        assertThat(runtime.containers()).containsOnlyKeys("other-start");
    }

    @Test
    @DisplayName("STARTING 저장이 다른 전이와 겹쳐 BROWSER_BUSY 가 나도 다시 읽어 RUNNING 이면 그 주소를 준다")
    void ensureRunningReadsAgainAfterBusySave() {
        UserBrowserRepository racing = mock(UserBrowserRepository.class, delegatesTo(repository));
        AtomicBoolean raced = new AtomicBoolean(false);
        doAnswer(call -> {
                    UserBrowser browser = call.getArgument(0);
                    if (browser.status() == UserBrowserStatus.STARTING && raced.compareAndSet(false, true)) {
                        runtime.plant("other-start", UserBrowserService.profileKey(101L), true);
                        markStatus(101L, "RUNNING", "other-start");
                    }
                    return repository.saveAndFlush(browser);
                })
                .when(racing)
                .saveAndFlush(any());
        UserBrowserService service = service(true, 2, racing);
        Long id = service.create(101L).id();
        service.sleeper = pause -> {};

        BrowserEndpoint endpoint = service.ensureRunning(101L);

        assertThat(raced).as("STARTING 저장이 겹쳤는지").isTrue();
        assertThat(endpoint).isEqualTo(new BrowserEndpoint(id, FAKE_CDP));
        assertThat(repository.findByUserId(101L).orElseThrow().containerId()).isEqualTo("other-start");
        assertThat(runtime.containers()).containsOnlyKeys("other-start");
    }

    @Test
    @DisplayName("브라우저가 없는 사용자의 중계 요청 둘이 함께 와도 한 번만 만들어 이긴 쪽의 프로필 디렉터리를 지우지 않는다")
    void ensureRunningCreatesOnceForConcurrentRequests() throws Exception {
        UserBrowserService service = service(true, 2);
        service.sleeper = pause -> awaitUntil(() -> repository
                .findByUserId(101L)
                .map(browser -> browser.status() == UserBrowserStatus.RUNNING)
                .orElse(false));
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean firstEnsure = new AtomicBoolean(true);
        // 먼저 온 요청을 만들기 도중(프로필 디렉터리를 만들기 직전)에 세운다
        beforeEnsure = () -> {
            if (firstEnsure.compareAndSet(true, false)) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        FutureTask<BrowserEndpoint> first = new FutureTask<>(() -> service.ensureRunning(101L));
        FutureTask<BrowserEndpoint> second = new FutureTask<>(() -> service.ensureRunning(101L));
        new Thread(first).start();
        awaitUntil(() -> !firstEnsure.get());
        Thread secondThread = new Thread(second);
        secondThread.start();
        // 고친 뒤에는 두 번째 요청이 잠금에서 기다린다. 고치기 전에는 기다리지 않고 지우기까지 간다
        awaitUntil(() -> secondThread.getState() == Thread.State.WAITING || profileCalls.size() > 1);
        release.countDown();

        BrowserEndpoint firstEndpoint = first.get(10, TimeUnit.SECONDS);
        BrowserEndpoint secondEndpoint = second.get(10, TimeUnit.SECONDS);

        assertThat(secondEndpoint).isEqualTo(firstEndpoint);
        assertThat(profileCalls).as("프로필 저장소 호출 %s", profileCalls).containsOnlyOnce("delete");
        assertThat(profileDirs).containsExactly(UserBrowserService.profileKey(101L));
        assertThat(runtime.containers()).hasSize(1);
    }

    @Test
    @DisplayName("기능이 꺼져 있으면 중계가 켜 둘 수 없어 BROWSER_DISABLED 다")
    void ensureRunningRejectsWhenDisabled() {
        assertCode(() -> service(false, 2).ensureRunning(101L), ErrorCode.BROWSER_DISABLED);
        assertThat(repository.findByUserId(101L)).isEmpty();
    }

    /** 조건이 참이 될 때까지 5초까지 기다린다. 넘으면 그대로 돌아가 뒤의 단언이 실패를 알린다. */
    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 다른 요청의 전이를 흉내 낸다. 버전을 올려 이 서비스가 쥔 줄이 낡게 한다. */
    private void markStatus(Long userId, String status, String containerId) {
        jdbc.update(
                "UPDATE user_browser SET status = ?, container_id = ?, version = version + 1 WHERE user_id = ?",
                status,
                containerId,
                userId);
    }

    private UserBrowserService service(boolean enabled, int maxRunning) {
        return service(enabled, maxRunning, repository);
    }

    private UserBrowserService service(boolean enabled, int maxRunning, UserBrowserRepository browsers) {
        BrowserProperties properties = new BrowserProperties(
                enabled,
                "https://browser-proxy.example.test",
                "example/browser:test",
                "example-browser",
                9999,
                "build/unused",
                "/example/browser-profiles",
                "/example/profile",
                512,
                1.0,
                256,
                128,
                maxRunning,
                Duration.ofMinutes(10),
                Duration.ofMillis(100),
                Duration.ofMinutes(30),
                null,
                null);
        BrowserProfileStore profiles = new BrowserProfileStore() {
            @Override
            public void ensure(String profileKey) {
                beforeEnsure.run();
                profileCalls.add("ensure");
                profileDirs.add(profileKey);
            }

            @Override
            public void delete(String profileKey) {
                profileCalls.add("delete");
                profileDirs.remove(profileKey);
            }
        };
        return new UserBrowserService(
                browsers,
                runtime,
                profiles,
                address -> cdpReady.get(),
                LiveProperties.fixed(BrowserProperties.class, properties),
                Clock.systemUTC(),
                new BrowserScreens(
                        new FakeCdp(),
                        new FakeCdp(),
                        new BrowserUsage(),
                        () -> Duration.ofMinutes(30),
                        Duration.ofSeconds(2)));
    }

    private static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(code));
    }
}
