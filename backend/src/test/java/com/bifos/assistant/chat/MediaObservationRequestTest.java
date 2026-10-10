package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.application.model.MediaObservationInput;
import com.bifos.assistant.chat.application.model.ObservationProvenance;
import com.bifos.assistant.chat.domain.type.ObservationProvenanceKind;
import com.bifos.assistant.chat.domain.type.ObservationStatus;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.chat.infra.MediaObservationRequestRepository;
import com.bifos.assistant.shared.error.ErrorCode;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class MediaObservationRequestTest extends ObservationFixture {
    @Test
    @DisplayName("서비스 재생성 뒤 모든 수락 UUID의 alias를 복원한다")
    void restoresEveryAcceptedAliasAfterServiceRestart() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        var first = record(0, a, input(), model());
        clock.advance(Duration.ofSeconds(1));
        var second = record(1, b, input(), model());
        var restarted = local(store, bodies, observations, requests, attachments, access);
        assertThat(restarted.record(owner, conversation.id(), photo.id(), 0, a, input(), model()))
                .isEqualTo(first);
        assertThat(restarted.record(owner, conversation.id(), photo.id(), 1, b, input(), model()))
                .isEqualTo(second);
        code(() -> record(1, b, input("변경"), model()), ErrorCode.MEDIA_OBSERVATION_CONFLICT);
        code(() -> record(0, UUID.randomUUID(), input(), model()), ErrorCode.MEDIA_OBSERVATION_CONFLICT);
        assertThat(observations.count()).isEqualTo(2);
        assertThat(requests.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("고정 배열 해시에 null을 포함하고 서버 시각을 제외한다")
    void hashesFixedArrayIncludingNullAndExcludingServerTimestamp() throws Exception {
        UUID id = UUID.randomUUID();
        var source = model();
        var first = record(0, id, input(), source);
        var changedTimestamp = new ObservationProvenance(
                source.kind(),
                source.executionId(),
                source.provider(),
                source.providerVersion(),
                source.model(),
                source.modelVersion(),
                source.schemaVersion(),
                source.promptVersion(),
                NOW);
        assertThat(record(0, id, input(), changedTimestamp)).isEqualTo(first);
        var digest = MessageDigest.getInstance("SHA-256");
        byte[] fixed = json.writeValueAsBytes(Arrays.asList(
                0L,
                input(),
                source.kind(),
                source.executionId(),
                source.provider(),
                source.providerVersion(),
                source.model(),
                source.modelVersion(),
                1,
                "media-observation-v1"));
        assertThat(requests.findAll().getFirst().requestHash())
                .isEqualTo(HexFormat.of().formatHex(digest.digest(fixed)));
        var changedModel = new ObservationProvenance(
                source.kind(),
                123L,
                source.provider(),
                source.providerVersion(),
                "another-model",
                source.modelVersion(),
                1,
                source.promptVersion(),
                null);
        code(() -> record(0, id, input(), changedModel), ErrorCode.MEDIA_OBSERVATION_CONFLICT);
    }

    @Test
    @DisplayName("coverage와 버전 변경은 저장된 요청 해시와 충돌한다")
    void detectsCoverageAndVersionChangesInPersistedRequestHash() {
        UUID id = UUID.randomUUID();
        record(0, id, input(), model());
        var differentCoverage = new MediaObservationInput(
                ObservationStatus.SUCCEEDED,
                input().summary(),
                List.of(),
                List.of(),
                new MediaObservationInput.Coverage("OVERVIEW", null, null),
                input().evidence(),
                null);
        code(() -> record(0, id, differentCoverage, model()), ErrorCode.MEDIA_OBSERVATION_CONFLICT);
        var differentVersion = new ObservationProvenance(
                ObservationProvenanceKind.MODEL_RESULT,
                123L,
                "provider",
                null,
                "model",
                "another-version",
                1,
                "media-observation-v1",
                null);
        code(() -> record(0, id, input(), differentVersion), ErrorCode.MEDIA_OBSERVATION_CONFLICT);
        code(() -> record(1, id, input(), model()), ErrorCode.MEDIA_OBSERVATION_CONFLICT);
        assertThat(observations.count()).isEqualTo(1);
        assertThat(requests.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("본문을 열 수 없어도 사용자 수정은 보호한다")
    void protectsUserCorrectionEvenWhenItsBodyCannotBeOpened() {
        UUID old = UUID.randomUUID();
        var first = record(0, old, input(), model());
        var user = new ObservationProvenance(
                ObservationProvenanceKind.USER_CORRECTION,
                null,
                null,
                null,
                null,
                null,
                1,
                "media-observation-v1",
                null);
        var correction = new MediaObservationInput(
                ObservationStatus.SUCCEEDED,
                "사용자 정정",
                List.of(),
                List.of(),
                input().coverage(),
                new MediaObservationInput.Evidence(ObservationProvenanceKind.USER_CORRECTION, "USER_CORRECTION"),
                null);
        record(1, UUID.randomUUID(), correction, user);
        jdbc.update("update media_observation set body='broken' where revision=2");
        code(() -> record(2, UUID.randomUUID(), input(), model()), ErrorCode.MEDIA_OBSERVATION_CONFLICT);
        assertThat(record(0, old, input(), model())).isEqualTo(first);
        var current = service.list(owner, conversation.id(), null, 10).getFirst();
        assertThat(current.revision()).isEqualTo(2);
        assertThat(current.provenance().kind()).isEqualTo(ObservationProvenanceKind.USER_CORRECTION);
        assertThat(current.observation()).isNull();
        assertThat(observations.count()).isEqualTo(2);
        assertThat(requests.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("alias 삽입 실패는 관찰과 암호화 본문을 함께 롤백한다")
    void rollsBackObservationAndEncryptedBodyWhenAliasInsertFails() {
        var broken = fail(MediaObservationRequestRepository.class, requests, "saveAndFlush", 1);
        var writing = local(store, bodies, observations, broken, attachments, access);
        assertThatThrownBy(() ->
                        writing.record(owner, conversation.id(), photo.id(), 0, UUID.randomUUID(), input(), model()))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(observations.count()).isZero();
        assertThat(requests.count()).isZero();
        record(0, UUID.randomUUID(), input(), model());
    }

    @Test
    @DisplayName("응답 SQL 실패 뒤에도 alias를 보존하고 재시도는 원래 시각을 반환한다")
    void preservesCommittedAliasWhenResponseSqlFailsAndRetryReturnsOriginalTimestamp() {
        UUID id = UUID.randomUUID();
        var broken = fail(
                ChatAttachmentRepository.class,
                attachments,
                "existsByIdAndConversationIdAndUploadedByUserIdAndMessageIdIsNotNullAndDeletedAtIsNullAndExpiresAtAfter",
                2);
        assertThatThrownBy(() -> local(store, bodies, observations, requests, broken, access)
                        .record(owner, conversation.id(), photo.id(), 0, id, input(), model()))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(observations.count()).isEqualTo(1);
        assertThat(requests.count()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(2));
        var retried = record(0, id, input(), model());
        assertThat(retried.revision()).isEqualTo(1);
        assertThat(retried.provenance().observedAt()).isEqualTo(NOW);
        assertThat(observations.count()).isEqualTo(1);
        assertThat(requests.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("소유자 SQL 실패는 본문 없음으로 바꾸지 않고 전파한다")
    void propagatesOwnerSqlFailureWithoutContentUnavailableFallback() {
        var broken = fail(ConversationRepository.class, conversations, "findByIdAndUserIdAndDeletedAtIsNull", 1);
        assertThatThrownBy(
                        () -> local(store, bodies, observations, requests, attachments, new ConversationAccess(broken))
                                .list(owner, conversation.id(), null, 10))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(observations.count()).isZero();
        assertThat(requests.count()).isZero();
    }
}
