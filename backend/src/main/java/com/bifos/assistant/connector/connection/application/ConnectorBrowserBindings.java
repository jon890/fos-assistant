package com.bifos.assistant.connector.connection.application;

import com.bifos.assistant.connector.connection.application.dto.BrowserBindingReinstall;
import com.bifos.assistant.connector.connection.application.dto.ConnectorBindingAttempt;
import com.bifos.assistant.connector.connection.application.dto.ResyncOutcome;
import com.bifos.assistant.connector.connection.domain.ConnectorBinding;
import com.bifos.assistant.connector.connection.infra.ConnectorBindingRepository;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 브라우저 중계 서명 비밀을 바꾼 뒤 브라우저를 쓰는 플러그인의 바인딩을 모두 다시 설치한다(서버 비밀값 스펙 REQ18).
 *
 * <p>재시작 대기 바인딩도 옛 표식을 쥐고 있으므로 상태와 상관없이 대상이다. 바인딩마다 따로 시작 트랜잭션을 연다. 연결 주인 행을 잠근 뒤
 * 바인딩을 다시 읽고, 설치 입력을 커밋한 다음 트랜잭션 밖에서 대시보드를 부른다. 다시 보내는 설치는 값을 바꾼 연결과 같은 방식
 * ({@link ConnectorBindingService#reinstall})이라 대시보드가 재시작 필요를 답하고 바인딩은 재시작 대기가 된다.
 *
 * <p>이 클래스의 메서드에 {@code @Transactional} 을 붙이지 않는다. 외부 호출을 트랜잭션 밖에서 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorBrowserBindings {

    private final ConnectorBindingRepository bindings;
    private final HermesConnectorClient connector;
    private final ConnectorInstallAttempts attempts;
    private final Clock clock;


    /** 브라우저를 쓰는 플러그인의 바인딩 수다. 확인 창이 보인다. 플러그인 목록을 읽지 못하면 비어 있다. */
    public OptionalInt count() {
        Optional<Map<String, ConnectorManifest>> browsers = browserManifests();
        if (browsers.isEmpty()) {
            return OptionalInt.empty();
        }
        Set<String> ids = browsers.get().keySet();
        return OptionalInt.of(ids.isEmpty() ? 0 : bindings.findIdsByConnectorIdIn(ids).size());
    }


    /**
     * 모두 다시 설치한다. 한 바인딩의 시작 예외나 {@code CALL_FAILED} 는 그 바인딩의 실패로 세고 다음으로 간다. 플러그인 목록을 읽지
     * 못하면 예외를 던지지 않고 그렇다고 돌려준다. 비밀은 이미 바뀌었기 때문이다.
     */
    public BrowserBindingReinstall reinstallAll() {
        Optional<Map<String, ConnectorManifest>> found = browserManifests();
        if (found.isEmpty()) {
            return new BrowserBindingReinstall(0, 0, 0, true);
        }
        Map<String, ConnectorManifest> browsers = found.get();
        if (browsers.isEmpty()) {
            return new BrowserBindingReinstall(0, 0, 0, false);
        }
        List<Long> targets = attempts.start(() -> bindings.findIdsByConnectorIdIn(browsers.keySet()));
        int restartPending = 0;
        int failed = 0;
        for (Long bindingId : targets) {
            Optional<ResyncOutcome> outcome;
            try {
                ConnectorBindingAttempt attempt = attempts.start(() -> reinstallLocked(bindingId, browsers));
                if (attempt == null) {
                    continue;
                }
                outcome = attempts.execute(attempt);
            } catch (RuntimeException ex) {
                log.warn("browser binding reinstall failed binding={} exception={}", bindingId, ex.getClass().getName());
                failed++;
                continue;
            }
            if (outcome.orElse(null) == ResyncOutcome.CALL_FAILED) {
                failed++;
            } else if (outcome.orElse(null) == ResyncOutcome.RESTART_PENDING) {
                restartPending++;
            }
        }
        return new BrowserBindingReinstall(targets.size(), restartPending, failed, false);
    }


    /** 주인을 잠근 뒤 바인딩을 다시 읽어 설치 입력을 만든다. 그 사이 지워졌으면 null 이다. */
    private ConnectorBindingAttempt reinstallLocked(Long bindingId, Map<String, ConnectorManifest> browsers) {
        Optional<ConnectorBinding> found = bindings.findById(bindingId);
        if (found.isEmpty()) {
            return null;
        }
        attempts.lockOwner(found.get().connection().userId());
        Optional<ConnectorBinding> locked = bindings.findById(bindingId);
        if (locked.isEmpty()) {
            return null;
        }
        ConnectorManifest manifest = browsers.get(locked.get().connection().connectorId());
        return manifest == null ? null : attempts.begin(locked.get(), Optional.of(manifest), false, false, true, clock.instant());
    }


    private Optional<Map<String, ConnectorManifest>> browserManifests() {
        try {
            return Optional.of(ConnectorManifests.read(connector).stream().filter(ConnectorManifest::ownerBrowser)
                .collect(Collectors.toMap(ConnectorManifest::id, Function.identity())));
        } catch (RuntimeException ex) {
            log.warn("browser bindings: catalog unavailable exception={}", ex.getClass().getName());
            return Optional.empty();
        }
    }
}
