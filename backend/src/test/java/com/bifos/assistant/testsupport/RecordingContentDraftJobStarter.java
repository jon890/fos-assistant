package com.bifos.assistant.testsupport;

import com.bifos.assistant.connector.application.ContentDraftJobStarter;
import com.bifos.assistant.connector.application.model.ContentDraftSaveTicketPayload;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/**
 * 원고 저장 시작 전송의 대역이다. 받은 시작을 기록하고 검사가 정한 답을 낸다.
 *
 * <p>catalog 가 시작을 선언한 검사만 이 빈에 닿는다. 정하지 않으면 접수 여부를 모르는 예외를 던진다.
 */
public class RecordingContentDraftJobStarter implements ContentDraftJobStarter {
    /** 받은 시작 한 번이다. ticket 원문은 검사가 authorize 에 다시 싣는 데만 쓴다. */
    public record Started(String profile, String connectorId, JsonNode snapshot, UUID jobId, String ticket) {
    }


    private final List<Started> started = new CopyOnWriteArrayList<>();
    private volatile Function<Started, Optional<String>> answer = ignored -> {
        throw new IllegalStateException("content draft starter is not configured");
    };


    @Override
    public Optional<String> start(String profile, String connectorId, JsonNode snapshot, ContentDraftSaveTicketPayload job, String ticket) {
        var call = new Started(profile, connectorId, snapshot.deepCopy(), job.jobId(), ticket);
        started.add(call);
        return answer.apply(call);
    }


    public void answer(Function<Started, Optional<String>> next) {
        this.answer = next;
    }


    public List<Started> started() {
        return List.copyOf(started);
    }


    public void reset() {
        started.clear();
        answer = ignored -> {
            throw new IllegalStateException("content draft starter is not configured");
        };
    }
}
