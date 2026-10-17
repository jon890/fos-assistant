package com.bifos.assistant.connector.contentdraft.application;

import com.bifos.assistant.connector.contentdraft.application.port.ContentDraftJobStarter;
import com.bifos.assistant.connector.contentdraft.application.dto.ContentDraftSaveTicketPayload;
import com.bifos.assistant.chat.domain.type.ContentDraftJobStatus;
import com.bifos.assistant.hermes.infra.HttpContentDraftSnapshotClient;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 실제 전송을 한 번만 실행하고 시작 전 거절과 결과 모름을 구분한다. */
@Component
@RequiredArgsConstructor
public class HttpContentDraftJobStarter implements ContentDraftJobStarter {
    private final HttpContentDraftSnapshotClient client;


    @Override
    public Optional<String> start(String profile, String connectorId, JsonNode snapshot, ContentDraftSaveTicketPayload job, String ticket) {
        var stamp = ContentDraftWorkerStatusDecoder.stamp(profile, job);
        var result = ContentDraftWorkerStatusDecoder.decode(client.start(profile, connectorId, snapshot, stamp, ticket), stamp, true);
        if (result.status() == ContentDraftJobStatus.UNKNOWN) {
            throw ContentDraftWorkerStatusDecoder.unavailable();
        }
        return result.status() == ContentDraftJobStatus.FAILED
            ? Optional.of(result.errorCode() == null ? "start_failed" : result.errorCode()) : Optional.empty();
    }
}
