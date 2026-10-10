package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.application.model.MediaObservationInput;
import com.bifos.assistant.chat.application.model.MediaObservationInput.Coverage;
import com.bifos.assistant.chat.application.model.MediaObservationInput.Evidence;
import com.bifos.assistant.chat.application.model.MediaObservationInput.Region;
import com.bifos.assistant.chat.application.model.ObservationProvenance;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.type.ObservationProvenanceKind;
import com.bifos.assistant.chat.domain.type.ObservationStatus;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.infra.MediaObservationBodies;
import com.bifos.assistant.chat.infra.MediaObservationRepository;
import com.bifos.assistant.chat.infra.MediaObservationRequestRepository;
import com.bifos.assistant.crypto.application.DataKeyService;
import com.bifos.assistant.crypto.application.DataKeyWriter;
import com.bifos.assistant.crypto.domain.KeyEncryptionKeys;
import com.bifos.assistant.crypto.domain.SealedText;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.crypto.infra.DataEncryptionProperties;
import com.bifos.assistant.crypto.infra.UserDataKeyRepository;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;

class MediaObservationCacheTest extends MediaObservationRequestTest {
    @Autowired
    UserDataKeyRepository keys;

    @Autowired
    DataKeyWriter keyWriter;

    @Autowired
    KeyEncryptionKeys keks;

    @Autowired
    DataEncryptionProperties encryption;

    @Test
    @DisplayName("같은 조건의 다른 실행과 본문은 최초 관찰을 유지하고 재시작 alias를 복원한다")
    void preservesInitialObservationAndRestoresCacheAliasAfterRestart() {
        var first = record(0, UUID.randomUUID(), input(), model());
        var row = observations.findAll().getFirst();
        UUID alias = UUID.randomUUID();
        clock.advance(Duration.ofSeconds(2));
        var source = source("provider", "provider-v2", "model", "model-v3", 456L);
        var submitted = complete(ObservationStatus.PARTIAL, "다른 관찰", input().coverage(), "456");
        assertThat(record(1, alias, submitted, source)).isEqualTo(first);
        assertThat(observations.findAll()).singleElement().satisfies(saved -> {
            assertThat(saved.id()).isEqualTo(row.id());
            assertThat(saved.body()).isEqualTo(row.body());
            assertThat(saved.analysisKey()).isEqualTo(row.analysisKey());
        });
        assertThat(requests.count()).isEqualTo(2);
        var restarted = local(store, bodies, observations, requests, attachments, access);
        assertThat(restarted.record(owner, conversation.id(), photo.id(), 1, alias, submitted, source))
                .isEqualTo(first);
        code(() -> record(1, alias, input(), model()), ErrorCode.MEDIA_OBSERVATION_CONFLICT);
        code(() -> record(0, UUID.randomUUID(), submitted, source), ErrorCode.MEDIA_OBSERVATION_CONFLICT);
    }

    @Test
    @DisplayName("완료 세 상태의 모든 후보와 제출 조합은 후보 상태를 보존한다")
    void reusesAllCompletedStatusCombinations() {
        for (var candidate : completedStatuses()) {
            for (var submitted : completedStatuses()) {
                photo = photo();
                var first = record(0, UUID.randomUUID(), complete(candidate, "처음", input().coverage(), "123"), model());
                assertThat(record(1, UUID.randomUUID(), complete(submitted, "나중", input().coverage(), "123"), model()))
                        .isEqualTo(first);
            }
        }
        assertThat(observations.count()).isEqualTo(9);
        assertThat(requests.count()).isEqualTo(18);
    }

    @Test
    @DisplayName("허용 조건의 변경은 새 revision이고 미지원 schema와 prompt는 거부한다")
    void createsNewRevisionsForChangedConditionsAndRejectsUnsupportedVersions() {
        var conditions = List.of(
                source("Provider", "provider-v2", "model", "model-v3", 123L),
                source("provider", null, "model", "model-v3", 123L),
                source("provider", "", "model", "model-v3", 123L),
                source("provider", "provider-v2", "another", "model-v3", 123L),
                changedModel());
        for (var source : conditions) {
            photo = photo();
            record(0, UUID.randomUUID(), input(), model());
            assertThat(record(1, UUID.randomUUID(), input(), source).revision()).isEqualTo(2);
        }
        for (var coverage : List.of(
                new Coverage("OVERVIEW", null, null),
                new Coverage("FIRST_FRAME", null, 0),
                new Coverage("CROP", new Region(0, .25, .5, .5), null))) {
            photo = photo();
            record(0, UUID.randomUUID(), input(), model());
            assertThat(record(
                                    1,
                                    UUID.randomUUID(),
                                    complete(ObservationStatus.SUCCEEDED, "관찰", coverage, "123"),
                                    model())
                            .revision())
                    .isEqualTo(2);
        }
        long count = observations.count();
        for (var invalid : List.of(
                new ObservationProvenance(
                        model().kind(), 123L, "provider", null, "model", null, 2, model().promptVersion(), null),
                new ObservationProvenance(model().kind(), 123L, "provider", null, "model", null, 1, "v2", null))) {
            code(() -> record(2, UUID.randomUUID(), input(), invalid), ErrorCode.VALIDATION_FAILED);
        }
        assertThat(observations.count()).isEqualTo(count);
    }

    @Test
    @DisplayName("처리 실패 구행과 과거 완료는 현재 후보로 재사용하지 않는다")
    void excludesProcessingFailedLegacyAndHistoricalRows() {
        for (var initial : List.of(
                new MediaObservationInput(
                        ObservationStatus.PROCESSING, null, null, null, input().coverage(), null, null),
                new MediaObservationInput(ObservationStatus.FAILED, null, null, null, null, null, "MODEL_ERROR"))) {
            photo = photo();
            record(0, UUID.randomUUID(), initial, model());
            assertThat(observations
                            .findFirstByAttachmentIdOrderByRevisionDesc(photo.id())
                            .orElseThrow()
                            .analysisKey())
                    .isNull();
            assertThat(record(1, UUID.randomUUID(), input(), model()).revision())
                    .isEqualTo(2);
            assertThat(record(2, UUID.randomUUID(), initial, model()).revision())
                    .isEqualTo(3);
            assertThat(observations
                            .findFirstByAttachmentIdOrderByRevisionDesc(photo.id())
                            .orElseThrow()
                            .analysisKey())
                    .isNull();
        }
        photo = photo();
        record(0, UUID.randomUUID(), input(), model());
        jdbc.update("update media_observation set analysis_key=null where attachment_id=?", photo.id());
        assertThat(record(1, UUID.randomUUID(), input(), model()).revision()).isEqualTo(2);
        assertThat(record(2, UUID.randomUUID(), input(), changedModel()).revision())
                .isEqualTo(3);
        assertThat(record(3, UUID.randomUUID(), input(), model()).revision()).isEqualTo(4);
    }

    @Test
    @DisplayName("유효한 평문은 목록과 UUID에서 읽되 새 cache alias는 만들지 않는다")
    void preservesPlainCompatibilityButMissesForNewCacheAlias() {
        UUID initial = UUID.randomUUID();
        var first = record(0, initial, input(), model());
        jdbc.update("update media_observation set body=?,body_key_id=null", json.writeValueAsString(input()));
        assertThat(service.list(owner, conversation.id(), null, 10).getFirst()).isEqualTo(first);
        assertThat(record(0, initial, input(), model())).isEqualTo(first);
        assertThat(record(1, UUID.randomUUID(), input(), model()).revision()).isEqualTo(2);
        assertThat(observations
                        .findFirstByAttachmentIdOrderByRevisionDesc(photo.id())
                        .orElseThrow()
                        .bodyKeyId())
                .isNotNull();
    }

    @Test
    @DisplayName("본문과 분석 key 손상은 새 암호화 revision으로 대체한다")
    void missesCorruptBodyAndAnalysisKeyAndWritesEncryptedRevision() {
        for (int mode = 0; mode < 11; mode++) {
            photo = photo();
            record(0, UUID.randomUUID(), input(), model());
            var row = observations
                    .findFirstByAttachmentIdOrderByRevisionDesc(photo.id())
                    .orElseThrow();
            if (mode == 0) {
                jdbc.update("update media_observation set body=null where id=?", row.id());
            } else if (mode == 1) {
                jdbc.update("update media_observation set body_key_id=999999999 where id=?", row.id());
            } else if (mode == 2) {
                jdbc.update("update media_observation set body='v1.broken' where id=?", row.id());
            } else if (mode == 3) {
                jdbc.update("update media_observation set analysis_key=? where id=?", "f".repeat(64), row.id());
            } else {
                String plain = switch (mode) {
                    case 4 -> "{";
                    case 5 -> "x".repeat(MediaObservationInput.MAX_BODY_BYTES + 1);
                    case 6 -> json.writeValueAsString(input("x".repeat(2001)));
                    case 7 ->
                        json.writeValueAsString(
                                complete(ObservationStatus.NEEDS_REVIEW, "본문", input().coverage(), "123"));
                    case 8 ->
                        json.writeValueAsString(complete(
                                ObservationStatus.SUCCEEDED, "본문", new Coverage("OVERVIEW", null, null), "123"));
                    default ->
                        json.writeValueAsString(complete(ObservationStatus.SUCCEEDED, "본문", input().coverage(), "456"));
                };
                String aad = "media_observation:" + (mode == 9 ? row.id() + 1 : row.id()) + ":conversation:"
                        + row.conversationId() + ":user:" + row.ownerUserId();
                var sealed = cipher.seal(owner.id(), aad, plain).orElseThrow();
                jdbc.update(
                        "update media_observation set body=?,body_key_id=? where id=?",
                        sealed.content(),
                        sealed.keyId(),
                        row.id());
            }
            assertThat(record(1, UUID.randomUUID(), input(), model()).revision())
                    .as("손상 종류 %s", mode)
                    .isEqualTo(2);
        }
        assertThat(observations.count()).isEqualTo(22);
        assertThat(requests.count()).isEqualTo(22);
    }

    @Test
    @DisplayName("원본 파일만 없어져도 새 UUID와 과거 UUID는 거절하고 목록 본문을 차단한다")
    void blocksBothRequestKindsAndListWhenSourceFileDisappears() throws IOException {
        UUID first = UUID.randomUUID();
        record(0, first, input(), model());
        Files.delete(file(photo));
        code(() -> record(1, UUID.randomUUID(), input(), model()), ErrorCode.ATTACHMENT_GONE);
        code(() -> record(0, first, input(), model()), ErrorCode.ATTACHMENT_GONE);
        assertThat(service.list(owner, conversation.id(), null, 10).getFirst().observation())
                .isNull();
        assertThat(observations.count()).isEqualTo(1);
        assertThat(requests.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("암호화 쓰기를 꺼도 기존 cache는 읽고 miss의 새 쓰기는 롤백한다")
    void reusesWithEncryptionDisabledButRollsBackMiss() {
        var first = record(0, UUID.randomUUID(), input(), model());
        TextCipher disabled = new TextCipher() {
            @Override
            public boolean enabled() {
                return false;
            }

            @Override
            public Optional<SealedText> seal(Long owner, String aad, String plain) {
                throw new AssertionError("새 암호화 금지");
            }

            @Override
            public Optional<String> open(Long key, Long owner, String aad, String sealed) {
                return cipher.open(key, owner, aad, sealed);
            }
        };
        var writing = local(store, new MediaObservationBodies(disabled), observations, requests, attachments, access);
        assertThat(writing.record(owner, conversation.id(), photo.id(), 1, UUID.randomUUID(), input("다른 본문"), model()))
                .isEqualTo(first);
        jdbc.update("update media_observation set body=null");
        code(
                () -> writing.record(owner, conversation.id(), photo.id(), 1, UUID.randomUUID(), input(), model()),
                ErrorCode.MEDIA_ENCRYPTION_UNAVAILABLE);
        assertThat(observations.count()).isEqualTo(1);
        assertThat(requests.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("cache 조회와 alias SQL 실패는 전파하며 alias 실패는 롤백한다")
    void propagatesCacheSqlFailuresAndRollsBackAliasFailure() {
        record(0, UUID.randomUUID(), input(), model());
        var brokenRows =
                fail(MediaObservationRepository.class, observations, "findFirstByAttachmentIdOrderByRevisionDesc", 1);
        assertThatThrownBy(() -> local(store, bodies, brokenRows, requests, attachments, access)
                        .record(owner, conversation.id(), photo.id(), 1, UUID.randomUUID(), input(), model()))
                .isInstanceOf(DataAccessResourceFailureException.class);
        var brokenAliases = fail(MediaObservationRequestRepository.class, requests, "saveAndFlush", 1);
        assertThatThrownBy(() -> local(store, bodies, observations, brokenAliases, attachments, access)
                        .record(owner, conversation.id(), photo.id(), 1, UUID.randomUUID(), input(), model()))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(observations.count()).isEqualTo(1);
        assertThat(requests.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("cache 복호화의 실제 DEK SQL 실패는 miss로 바꾸지 않는다")
    void propagatesRealDekSqlFailureInsideCacheLookup() {
        record(0, UUID.randomUUID(), input(), model());
        var brokenKeys = fail(UserDataKeyRepository.class, keys, "findById", 1);
        var fresh = new DataKeyService(keks, keyWriter, brokenKeys, encryption, clock);
        var reading = local(store, new MediaObservationBodies(fresh), observations, requests, attachments, access);
        UUID alias = UUID.randomUUID();
        assertThatThrownBy(() -> reading.record(owner, conversation.id(), photo.id(), 1, alias, input(), model()))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(observations.count()).isEqualTo(1);
        assertThat(requests.count()).isEqualTo(1);
        assertThat(reading.record(owner, conversation.id(), photo.id(), 1, alias, input(), model())
                        .revision())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("cache 응답 마지막 SQL 실패 뒤 커밋 alias로 재시도한다")
    void preservesCacheAliasAfterFinalResponseSqlFailure() {
        var first = record(0, UUID.randomUUID(), input(), model());
        var broken = fail(
                ChatAttachmentRepository.class,
                attachments,
                "existsByIdAndConversationIdAndUploadedByUserIdAndMessageIdIsNotNullAndDeletedAtIsNullAndExpiresAtAfter",
                3);
        UUID alias = UUID.randomUUID();
        assertThatThrownBy(() -> local(store, bodies, observations, requests, broken, access)
                        .record(owner, conversation.id(), photo.id(), 1, alias, input(), model()))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(observations.count()).isEqualTo(1);
        assertThat(requests.count()).isEqualTo(2);
        assertThat(record(1, alias, input(), model())).isEqualTo(first);
    }

    @Test
    @DisplayName("다른 사용자의 같은 원본은 별도 관찰과 alias를 갖는다")
    void isolatesIdenticalBytesBetweenUsers() {
        var first = record(0, UUID.randomUUID(), input(), model());
        var other = user();
        var target = conversations.save(Conversation.startedBy(other.id(), "다른 관찰", null, NOW));
        var otherPhoto = photo(other, target);
        var second = service.record(other, target.id(), otherPhoto.id(), 0, UUID.randomUUID(), input(), model());
        assertThat(second.sourceFingerprint()).isEqualTo(first.sourceFingerprint());
        assertThat(second.assetId()).isNotEqualTo(first.assetId());
        assertThat(observations.count()).isEqualTo(2);
        assertThat(requests.findAll())
                .extracting(value -> value.observationId())
                .doesNotHaveDuplicates();
    }

    private static List<ObservationStatus> completedStatuses() {
        return List.of(ObservationStatus.SUCCEEDED, ObservationStatus.PARTIAL, ObservationStatus.NEEDS_REVIEW);
    }

    private static MediaObservationInput complete(
            ObservationStatus status, String summary, Coverage coverage, String execution) {
        return new MediaObservationInput(
                status,
                summary,
                List.of(),
                status == ObservationStatus.PARTIAL ? List.of("불확실") : List.of(),
                coverage,
                new Evidence(ObservationProvenanceKind.MODEL_RESULT, execution),
                null);
    }

    private static ObservationProvenance source(
            String provider, String providerVersion, String model, String modelVersion, Long execution) {
        return new ObservationProvenance(
                ObservationProvenanceKind.MODEL_RESULT,
                execution,
                provider,
                providerVersion,
                model,
                modelVersion,
                1,
                "media-observation-v1",
                null);
    }
}
