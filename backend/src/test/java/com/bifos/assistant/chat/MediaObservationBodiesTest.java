package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.domain.MediaObservation;
import com.bifos.assistant.chat.infra.MediaObservationBodies;
import com.bifos.assistant.crypto.application.DataKeyService;
import com.bifos.assistant.crypto.application.DataKeyWriter;
import com.bifos.assistant.crypto.domain.KeyEncryptionKeys;
import com.bifos.assistant.crypto.domain.SealedText;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.crypto.infra.DataEncryptionProperties;
import com.bifos.assistant.crypto.infra.FileKeyEncryptionKeys;
import com.bifos.assistant.crypto.infra.UserDataKeyRepository;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

class MediaObservationBodiesTest extends ObservationFixture {
    @Autowired
    DataKeyWriter keyWriter;

    @Autowired
    UserDataKeyRepository keys;

    @Autowired
    KeyEncryptionKeys keks;

    @Autowired
    DataEncryptionProperties cryptoProperties;

    @Test
    @DisplayName("실제 암호화 왕복과 행 대화 소유자 AAD를 각각 검증한다")
    void roundTripsRealCipherAndIndependentlyBindsRowConversationAndOwner() {
        record(0, UUID.randomUUID(), input(), model());
        var row = observations.findAll().getFirst();
        String plain = json.writeValueAsString(input());
        assertThat(row.bodyKeyId()).isNotNull();
        assertThat(row.body()).startsWith("v1.").doesNotContain("관찰 표식");
        assertThat(bodies.open(row, owner.id())).contains(plain);
        for (String field : new String[] {"id", "conversationId", "ownerUserId"}) {
            var copy = copied(row);
            ReflectionTestUtils.setField(copy, field, ((Long) ReflectionTestUtils.getField(copy, field)) + 1);
            String changedAad = "media_observation:" + copy.id() + ":conversation:" + copy.conversationId() + ":user:"
                    + copy.ownerUserId();
            assertThat(cipher.open(row.bodyKeyId(), owner.id(), changedAad, row.body()))
                    .as(field + "만 바꾸고 key와 복호화 사용자는 그대로 둔 독립 AAD 실패")
                    .isEmpty();
        }
        assertThat(bodies.open(row, owner.id())).contains(plain);
    }

    @Test
    @DisplayName("활성 플래그 변경은 기존 암호문을 열고 새 캐시의 KEK 부재는 실패한다")
    void opensExistingCipherWhenOnlyEnabledFlagChangesButMissingKekFailsWithFreshCache() {
        record(0, UUID.randomUUID(), input(), model());
        var row = observations.findAll().getFirst();
        String expected = json.writeValueAsString(input());
        assertThat(bodies.open(row, owner.id())).contains(expected);
        TextCipher disabled = new TextCipher() {
            @Override
            public boolean enabled() {
                return false;
            }

            @Override
            public Optional<SealedText> seal(Long owner, String aad, String plain) {
                return cipher.seal(owner, aad, plain);
            }

            @Override
            public Optional<String> open(Long keyId, Long owner, String aad, String sealed) {
                return cipher.open(keyId, owner, aad, sealed);
            }
        };
        assertThat(new MediaObservationBodies(disabled).open(row, owner.id())).contains(expected);
        var missingProperties = new DataEncryptionProperties("", "", false, null, 100);
        var missing = new DataKeyService(
                new FileKeyEncryptionKeys(missingProperties), keyWriter, keys, missingProperties, clock);
        missing.forgetCachedKeys();
        assertThat(new MediaObservationBodies(missing).open(row, owner.id())).isEmpty();
        code(
                () -> local(store, new MediaObservationBodies(disabled), observations, requests, attachments, access)
                        .record(owner, conversation.id(), photo.id(), 1, UUID.randomUUID(), input(), changedModel()),
                ErrorCode.MEDIA_ENCRYPTION_UNAVAILABLE);
        assertThat(observations.count()).isEqualTo(1);
        assertThat(requests.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("빈 암호화 결과와 암호 오류는 전체 저장을 롤백한다")
    void refusesEmptySealAndCryptographicFailureAndRollsBackEntireWrite() {
        for (boolean cryptoFailure : new boolean[] {false, true}) {
            var failing = new TextCipher() {
                @Override
                public boolean enabled() {
                    return true;
                }

                @Override
                public Optional<SealedText> seal(Long owner, String aad, String plain) {
                    if (cryptoFailure) {
                        throw new IllegalStateException("synthetic crypto failure");
                    }
                    return Optional.empty();
                }

                @Override
                public Optional<String> open(Long keyId, Long owner, String aad, String sealed) {
                    return cipher.open(keyId, owner, aad, sealed);
                }
            };
            code(
                    () -> local(store, new MediaObservationBodies(failing), observations, requests, attachments, access)
                            .record(owner, conversation.id(), photo.id(), 0, UUID.randomUUID(), input(), model()),
                    ErrorCode.MEDIA_ENCRYPTION_UNAVAILABLE);
            assertThat(observations.count()).isZero();
            assertThat(requests.count()).isZero();
        }
        record(0, UUID.randomUUID(), input(), model());
    }

    @Test
    @DisplayName("null 키의 평문 호환만 읽고 깨진 JSON과 빈 본문을 거부한다")
    void readsNullKeyPlainCompatibilityAndRejectsBrokenJsonAndAbsentBody() {
        record(0, UUID.randomUUID(), input(), model());
        jdbc.update("update media_observation set body=?, body_key_id=null", json.writeValueAsString(input()));
        assertThat(service.list(owner, conversation.id(), null, 10).getFirst().observation())
                .isEqualTo(input());
        for (String body : new String[] {"{", "null", "{}", null}) {
            jdbc.update("update media_observation set body=?", body);
            var result = service.list(owner, conversation.id(), null, 10).getFirst();
            assertThat(result.observation()).isNull();
            assertThat(result.errorCode()).isEqualTo("CONTENT_UNAVAILABLE");
        }
    }

    @Test
    @DisplayName("실제 DEK SQL 실패를 전파하고 오류를 캐시하지 않아 복구한다")
    void propagatesRealDekRepositoryFailureAndRecoversWithoutFailureCache() {
        record(0, UUID.randomUUID(), input(), model());
        var row = observations.findAll().getFirst();
        assertThat(bodies.open(row, owner.id())).isPresent();
        var failingKeys = fail(UserDataKeyRepository.class, keys, "findById", 1);
        var fresh = new DataKeyService(keks, keyWriter, failingKeys, cryptoProperties, clock);
        var reading = new MediaObservationBodies(fresh);
        assertThatThrownBy(() -> reading.open(row, owner.id())).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(reading.open(row, owner.id())).contains(json.writeValueAsString(input()));
    }

    @Test
    @DisplayName("암호화의 DEK SQL 실패는 revision과 alias를 롤백한다")
    void propagatesDekLookupSqlFailureDuringSealAndRollsBackRevisionAndAlias() {
        record(0, UUID.randomUUID(), input(), model());
        var failingKeys = fail(UserDataKeyRepository.class, keys, "findByUserId", 1);
        var localWriter = new DataKeyWriter(failingKeys, keks, clock);
        var fresh = new DataKeyService(keks, localWriter, failingKeys, cryptoProperties, clock);
        var writing = local(store, new MediaObservationBodies(fresh), observations, requests, attachments, access);
        UUID request = UUID.randomUUID();
        assertThatThrownBy(
                        () -> writing.record(owner, conversation.id(), photo.id(), 1, request, input(), changedModel()))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(observations.count()).isEqualTo(1);
        assertThat(requests.count()).isEqualTo(1);
        assertThat(writing.record(owner, conversation.id(), photo.id(), 1, request, input(), changedModel())
                        .revision())
                .isEqualTo(2);
        assertThat(requests.count()).isEqualTo(2);
    }

    private MediaObservation copied(MediaObservation source) {
        var result = new MediaObservation(
                photo,
                source.revision(),
                source.sourceFingerprint(),
                source.status(),
                source.provenanceKind(),
                source.schemaVersion(),
                source.promptVersion(),
                source.originExecutionId(),
                source.provider(),
                source.providerVersion(),
                source.model(),
                source.modelVersion(),
                source.analysisKey(),
                source.createdAt());
        ReflectionTestUtils.setField(result, "id", source.id());
        result.seal(source.body(), source.bodyKeyId());
        return result;
    }
}
