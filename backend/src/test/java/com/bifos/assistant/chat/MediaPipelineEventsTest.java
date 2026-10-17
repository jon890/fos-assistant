package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bifos.assistant.chat.attachment.application.dto.AgentPhoto;
import com.bifos.assistant.chat.attachment.application.MediaPipelineEvents;
import com.bifos.assistant.chat.contentdraft.domain.entity.ContentDraftAsset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MediaPipelineEventsTest {
    private static final String PREFIX = "media-pipeline ";
    private final Logger logger = (Logger)LoggerFactory.getLogger(MediaPipelineEvents.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final JsonMapper json = JsonMapper.builder().build();


    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
    }


    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }


    @Test
    @DisplayName("실행 입력 사건은 실은 사진의 자리와 싣지 못한 첨부 번호만 남긴다")
    void embeddedRecordsPositionsAndMissingIdsOnly() {
        MediaPipelineEvents.embedded(7L, List.of(
            new AgentPhoto(11L, 1, "data:image/jpeg;base64,/9j/SECRET"),
            new AgentPhoto(12L, 2, null),
            new AgentPhoto(13L, 3, "data:image/jpeg;base64,/9j/OTHER")));

        JsonNode event = onlyEvent();
        assertThat(event.path("stage").asString()).isEqualTo("embedded");
        assertThat(event.path("count").asInt()).isEqualTo(2);
        assertThat(event.path("assets").get(0).path("assetId").asString()).isEqualTo("11");
        assertThat(event.path("assets").get(1).path("assetId").asString()).isEqualTo("13");
        assertThat(event.path("assets").get(1).path("position").asInt()).isEqualTo(1);
        assertThat(event.path("missing").get(0).asString()).isEqualTo("12");
        assertThat(logs.list.getFirst().getFormattedMessage()).doesNotContain("SECRET", "OTHER", "data:");
    }


    @Test
    @DisplayName("사진 없는 실행 입력은 사건을 남기지 않는다")
    void embeddedSkipsEmptyInput() {
        MediaPipelineEvents.embedded(7L, List.of());

        assertThat(logs.list).isEmpty();
    }


    @Test
    @DisplayName("원고 참조 사건은 커밋한 뒤에만 남고 블록 자리와 첨부 번호만 싣는다")
    void draftReferencedWaitsForCommit() {
        UUID draftId = UUID.randomUUID();
        TransactionSynchronizationManager.initSynchronization();
        MediaPipelineEvents.draftReferenced(7L, draftId.toString(), 2, List.of(
            new ContentDraftAsset(1L, 0, 21L, "a".repeat(64), null, null),
            new ContentDraftAsset(1L, 2, 22L, "b".repeat(64), 5L, 1L)));
        assertThat(logs.list).isEmpty();

        List<TransactionSynchronization> registered = TransactionSynchronizationManager.getSynchronizations();
        registered.forEach(TransactionSynchronization::afterCommit);

        JsonNode event = onlyEvent();
        assertThat(event.path("stage").asString()).isEqualTo("draftReferenced");
        assertThat(event.path("draftId").asString()).isEqualTo(draftId.toString());
        assertThat(event.path("revision").asLong()).isEqualTo(2);
        assertThat(event.path("count").asInt()).isEqualTo(2);
        assertThat(event.path("assets").get(1).path("assetId").asString()).isEqualTo("22");
        assertThat(event.path("assets").get(1).path("position").asInt()).isEqualTo(2);
        assertThat(logs.list.getFirst().getFormattedMessage()).doesNotContain("a".repeat(64), "b".repeat(64));
    }


    @Test
    @DisplayName("롤백한 원고 저장은 참조 사건을 남기지 않는다")
    void draftReferencedSkipsRollback() {
        TransactionSynchronizationManager.initSynchronization();
        MediaPipelineEvents.draftReferenced(7L, UUID.randomUUID().toString(), 1,
            List.of(new ContentDraftAsset(1L, 0, 21L, "a".repeat(64), null, null)));

        TransactionSynchronizationManager.getSynchronizations()
            .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(logs.list).isEmpty();
    }


    private JsonNode onlyEvent() {
        assertThat(logs.list).hasSize(1);
        String message = logs.list.getFirst().getFormattedMessage();
        assertThat(message).startsWith(PREFIX);
        JsonNode event = json.readTree(message.substring(PREFIX.length()));
        assertThat(event.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(event.path("conversationId").asLong()).isEqualTo(7L);
        return event;
    }
}
