package com.bifos.assistant.connector.connection.domain;

import com.bifos.assistant.connector.connection.domain.type.EventWatchResult;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 바인딩 하나의 사건 감지 상태다(ADR-20261105 / event-watch). {@code agent_connector_binding.event_watch_state} 에 JSON 글로 둔다.
 *
 * <p>확인마다 줄을 쌓지 않고 이 값 하나를 덮어쓴다. 칸의 뜻은 {@code backend/docs/data-schema.md} 의 「agent_connector_binding」 이
 * 갖는다.
 *
 * @param timeZone 켤 때 받은 IANA 시간대. 하루와 조용한 시간을 이 시간대로 센다
 * @param checkpoint 확인 지점. 결과를 다 쓴 마지막 조회를 시작한 Control Plane 의 시각이다. 메일의 {@code Date} 머리가 아니다
 * @param seen 겹침 구간에서 이미 본 식별자와 처음 본 시각
 * @param lastCheckedAt 마지막으로 확인한 시각
 * @param lastResult 마지막 확인의 결과
 * @param lastNewCount 마지막 확인에서 센 새 항목 수
 * @param failures 연결 문제로 연달아 실패한 수
 * @param day {@code runs} 를 센 날. {@code timeZone} 의 날짜다
 * @param runs 그날 연 사건 실행 수
 * @param enabledAt 켠 시각. 겹쳐 받더라도 이 시각 앞은 받지 않아 켜기 전에 온 메일로 깨우지 않는다
 * @param failureNotified 이번 연속 실패에 다시 연결 알림을 냈는가. 목록을 받으면 실패 수와 함께 비운다
 * @param deferred 조용한 시간에 미룬 알림. 없으면 null 이다
 */
public record EventWatchState(
    String timeZone,
    Instant checkpoint,
    List<Seen> seen,
    Instant lastCheckedAt,
    EventWatchResult lastResult,
    int lastNewCount,
    int failures,
    LocalDate day,
    int runs,
    Instant enabledAt,
    boolean failureNotified,
    Deferred deferred) {


    /** 모르는 칸과 없는 칸을 받는다. 앞 버전이 쓴 JSON 에 뒤에 더한 칸이 없어도 기본값으로 읽는다. */
    private static final JsonMapper JSON = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .build();

    public EventWatchState {
        seen = seen == null ? List.of() : List.copyOf(seen);
    }


    /**
     * 이미 본 식별자 하나다.
     *
     * @param at Control Plane 이 처음 본 시각. 겹침 구간을 벗어나면 지운다
     */
    public record Seen(String id, Instant at) {
    }

    /**
     * 조용한 시간에 끝난 사건 실행의 알림을 모아 둔 것이다. 아침의 첫 확인에서 한 건으로 낸다.
     *
     * @param runs 미룬 실행 수
     * @param newCount 그 실행들이 본 새 항목 수
     * @param notifyCount 그 실행들이 알릴 것으로 고른 항목 수
     * @param conversationId 마지막으로 미룬 실행의 점검 대화 번호. 아침 알림이 이 대화로 간다
     * @param sourceTitle 원천 이름. 아침 알림의 본문이다. 아침에 카탈로그를 읽지 못해도 알림을 낼 수 있게 둔다
     * @param noun 항목 이름. 예: 「메일」
     */
    public record Deferred(int runs, int newCount, int notifyCount, Long conversationId, String sourceTitle, String noun) {
    }


    /** 켤 때의 상태다. 지점은 켠 시각이라 켜기 전에 온 메일로 깨우지 않는다. */
    public static EventWatchState fresh(ZoneId zone, Instant now) {
        return new EventWatchState(zone.getId(), micros(now), List.of(), null, null, 0, 0, null, 0, micros(now), false, null);
    }


    public ZoneId zone() {
        return ZoneId.of(timeZone);
    }


    /** 다음 조회가 받을 시작 시각이다. 지점보다 {@code overlap} 앞이되 켠 시각보다 앞서지 않는다. */
    public Instant since(Duration overlap) {
        Instant overlapped = checkpoint.minus(overlap);
        return enabledAt != null && overlapped.isBefore(enabledAt) ? enabledAt : overlapped;
    }


    /** 그날 연 사건 실행 수다. 날이 바뀌었으면 0 이다. */
    public int runsOn(LocalDate today) {
        return today.equals(day) ? runs : 0;
    }


    /** 지점과 이미 본 식별자를 그대로 두고 마지막 결과만 적는다. */
    public EventWatchState checked(Instant now, EventWatchResult result, int newCount) {
        return new EventWatchState(timeZone, checkpoint, seen, micros(now), result, newCount, failures, day, runs, enabledAt,
            failureNotified, deferred);
    }


    /** 목록을 받았다. 연결 실패 수와 다시 연결 알림 표시를 비운다. 지점은 옮기지 않는다. */
    public EventWatchState reachable() {
        return new EventWatchState(timeZone, checkpoint, seen, lastCheckedAt, lastResult, lastNewCount, 0, day, runs, enabledAt,
            false, deferred);
    }


    /** 목록을 받지 못했다. 연결 문제({@code connectionProblem})일 때만 연속 실패 수를 늘린다. 지점은 옮기지 않는다. */
    public EventWatchState failed(Instant now, boolean connectionProblem) {
        return new EventWatchState(timeZone, checkpoint, seen, micros(now), EventWatchResult.FAILED, 0,
            connectionProblem ? failures + 1 : failures, day, runs, enabledAt, failureNotified, deferred);
    }


    /** 이번 연속 실패에 다시 연결 알림을 냈다고 적는다. */
    public EventWatchState failureNoticed() {
        return new EventWatchState(timeZone, checkpoint, seen, lastCheckedAt, lastResult, lastNewCount, failures, day, runs,
            enabledAt, true, deferred);
    }


    /** 조용한 시간에 끝난 사건 실행 하나의 알림을 미뤄 둔다. 앞서 미룬 것에 더하고 대화와 이름은 마지막 실행의 것으로 둔다. */
    public EventWatchState defer(int newCount, int notifyCount, Long conversationId, String sourceTitle, String noun) {
        Deferred next = deferred == null
            ? new Deferred(1, newCount, notifyCount, conversationId, sourceTitle, noun)
            : new Deferred(deferred.runs() + 1, deferred.newCount() + newCount, deferred.notifyCount() + notifyCount,
                conversationId, sourceTitle, noun);
        return new EventWatchState(timeZone, checkpoint, seen, lastCheckedAt, lastResult, lastNewCount, failures, day, runs,
            enabledAt, failureNotified, next);
    }


    /** 미룬 알림을 비운다. 아침 알림을 낸 뒤 부른다. */
    public EventWatchState withoutDeferred() {
        return new EventWatchState(timeZone, checkpoint, seen, lastCheckedAt, lastResult, lastNewCount, failures, day, runs,
            enabledAt, failureNotified, null);
    }


    /** 시간대만 바꾼다. 이미 켠 바인딩을 다시 켤 때 지점과 셈을 그대로 둔다. */
    public EventWatchState inZone(ZoneId zone) {
        return new EventWatchState(zone.getId(), checkpoint, seen, lastCheckedAt, lastResult, lastNewCount, failures, day, runs,
            enabledAt, failureNotified, deferred);
    }


    /**
     * 그 조회의 결과를 다 썼다. 지점을 옮기고 이미 본 식별자를 바꾼다.
     *
     * @param queriedAt 조회를 시작한 시각. 새 지점이다
     * @param nowSeen 겹침 구간 안에 남길 이미 본 식별자
     * @param woke 이번에 사건 실행을 열었으면 참. 그날의 실행 수를 하나 늘린다
     */
    public EventWatchState consumed(Instant queriedAt, List<Seen> nowSeen, boolean woke, LocalDate today) {
        int todayRuns = runsOn(today) + (woke ? 1 : 0);
        return new EventWatchState(timeZone, micros(queriedAt), nowSeen, lastCheckedAt, lastResult, lastNewCount, failures,
            today, todayRuns, enabledAt, failureNotified, deferred);
    }


    /** 저장할 JSON 글이다. */
    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JacksonException ex) {
            throw new IllegalStateException("cannot write event watch state", ex);
        }
    }


    /** 저장된 JSON 글을 읽는다. 비었으면 null 이다. 읽지 못하면 저장 오류로 전한다. */
    public static EventWatchState fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, EventWatchState.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("cannot read event watch state", ex);
        }
    }


    private static Instant micros(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }
}
