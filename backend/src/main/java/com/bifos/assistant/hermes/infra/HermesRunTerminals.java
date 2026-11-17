package com.bifos.assistant.hermes.infra;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 실행 하나의 끝 사건과, 이 프로세스가 그 실행에 한 일을 메모리에 짧게 둔다.
 *
 * <p>Hermes v0.23.2 의 실행 조회는 {@code error}, {@code pending_steer} 를 주지 않고 끝 사건에만 싣는다. 사건 스트림이 받은 끝 사건을
 * 실행 조회 쪽이 읽도록 이어 준다. 둘 다 {@code hermes/infra} 의 어댑터라 Hermes 의 사건 이름이 밖으로 나가지 않는다
 * (docs/features/hermes-upgrade/design.md 의 「결과 칸 채우기」). 프로세스가 다시 뜨면 비며, 그때는 「모른다」 가 맞는 답이다.
 */
@Component
public class HermesRunTerminals {

    /** 끝 사건과 중지 기록을 두는 시간이다. 실행 하나가 끝나고 조회가 결과를 읽기까지 걸리는 시간보다 넉넉하다. */
    static final Duration RETENTION = Duration.ofMinutes(30);

    /** 끝 상태를 확인한 run 을 다시 묻지 않을 시간이다. 잃은 실행 미리 풀기가 고르는 기간(24시간)과 같다. */
    static final Duration SETTLED_RETENTION = Duration.ofHours(24);

    /** Hermes 의 끝 사건 이름이다. */
    static final Set<String> TERMINAL_EVENTS = Set.of("run.completed", "run.failed", "run.cancelled", "run.interrupted");

    private final Map<String, Stamped<Terminal>> terminals = new ConcurrentHashMap<>();
    private final Map<String, Instant> stops = new ConcurrentHashMap<>();
    private final Map<String, Instant> settled = new ConcurrentHashMap<>();
    private final Clock clock;


    @Autowired
    public HermesRunTerminals() {
        this(Clock.systemUTC());
    }


    HermesRunTerminals(Clock clock) {
        this.clock = clock;
    }


    /**
     * 끝 사건 하나다.
     *
     * @param event Hermes 의 끝 사건 이름({@code run.completed} 등)
     * @param pendingSteerPresent 사건에 {@code pending_steer} 칸이 있었는가. 없으면 넘어간 글이 없다는 뜻이다. 정상 종료로 끊긴
     * {@code run.interrupted} 는 칸을 버리므로 이 값으로 판단하지 않는다
     */
    public record Terminal(
        String event,
        String error,
        String pendingSteer,
        boolean pendingSteerPresent,
        String servedModel,
        String servedProvider) {

        /** 끝 사건이면 {@link #of} 로 옮기고, 아니면 빈 값이다. 사건 스트림이 받은 사건마다 부른다. */
        static Optional<Terminal> ofTerminalEvent(String event, JsonNode root) {
            return TERMINAL_EVENTS.contains(event) ? Optional.of(of(event, root)) : Optional.empty();
        }


        /** 끝 사건 JSON 에서 실행 조회가 v0.23.2 에서 주지 않는 값을 꺼낸다. 칸은 최상위나 {@code data} 아래에 온다. */
        static Terminal of(String event, JsonNode root) {
            JsonNode payload = root.path("data");
            JsonNode pending = root.has("pending_steer") ? root.get("pending_steer") : payload.get("pending_steer");
            JsonNode runtime = root.has("runtime") ? root.path("runtime") : payload.path("runtime");
            String error = text(root, "error");
            String pendingText = pending == null || pending.isNull() ? null : pending.asText();
            return new Terminal(
                event,
                error == null ? text(payload, "error") : error,
                pendingText == null || pendingText.isBlank() ? null : pendingText,
                pending != null,
                text(runtime, "model"),
                text(runtime, "provider"));
        }


        private static String text(JsonNode node, String field) {
            JsonNode value = node.path(field);
            return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
        }


        public boolean interrupted() {
            return "run.interrupted".equals(event);
        }


        public boolean cancelled() {
            return "run.cancelled".equals(event);
        }
    }


    public void record(String profileName, String runId, Terminal terminal) {
        Instant now = clock.instant();
        sweep(now);
        terminals.put(key(profileName, runId), new Stamped<>(terminal, now));
    }


    /** 남긴 끝 사건을 꺼낸다. 꺼내면 지운다. */
    public Optional<Terminal> take(String profileName, String runId) {
        Stamped<Terminal> found = terminals.remove(key(profileName, runId));
        if (found == null || expired(found.at(), RETENTION, clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(found.value());
    }


    /** 이 프로세스가 그 run 에 중지를 보냈다. */
    public void markStopped(String profileName, String runId) {
        Instant now = clock.instant();
        sweep(now);
        stops.put(key(profileName, runId), now);
    }


    public boolean stopped(String profileName, String runId) {
        Instant at = stops.get(key(profileName, runId));
        return at != null && !expired(at, RETENTION, clock.instant());
    }


    /** 그 run 이 Hermes 에서 끝 상태인 것을 확인했다. 잃은 실행 미리 풀기가 다시 묻지 않는다. */
    public void markSettled(String profileName, String runId) {
        Instant now = clock.instant();
        sweep(now);
        settled.put(key(profileName, runId), now);
    }


    public boolean settled(String profileName, String runId) {
        Instant at = settled.get(key(profileName, runId));
        return at != null && !expired(at, SETTLED_RETENTION, clock.instant());
    }


    private void sweep(Instant now) {
        terminals.values().removeIf(stamped -> expired(stamped.at(), RETENTION, now));
        stops.values().removeIf(at -> expired(at, RETENTION, now));
        settled.values().removeIf(at -> expired(at, SETTLED_RETENTION, now));
    }


    private static boolean expired(Instant at, Duration retention, Instant now) {
        return at.plus(retention).isBefore(now);
    }


    private static String key(String profileName, String runId) {
        return profileName + "\n" + runId;
    }


    private record Stamped<T>(T value, Instant at) {
    }
}
