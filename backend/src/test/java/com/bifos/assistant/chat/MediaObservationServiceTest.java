package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.application.AttachmentCleaner;
import com.bifos.assistant.chat.application.AttachmentService;
import com.bifos.assistant.chat.application.ChatContentMutationCoordinator;
import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.application.MediaObservationCleaner;
import com.bifos.assistant.chat.application.MediaObservationService;
import com.bifos.assistant.chat.application.model.MediaObservationInput;
import com.bifos.assistant.chat.application.model.MediaObservationInput.Coverage;
import com.bifos.assistant.chat.application.model.MediaObservationInput.Evidence;
import com.bifos.assistant.chat.application.model.MediaObservationView;
import com.bifos.assistant.chat.application.model.ObservationProvenance;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.type.ObservationProvenanceKind;
import com.bifos.assistant.chat.domain.type.ObservationStatus;
import com.bifos.assistant.chat.infra.AttachmentProperties;
import com.bifos.assistant.chat.infra.AttachmentStore;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.chat.infra.MediaObservationBodies;
import com.bifos.assistant.chat.infra.MediaObservationRepository;
import com.bifos.assistant.chat.infra.MediaObservationRequestRepository;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

class MediaObservationServiceTest extends ObservationFixture {
    @Test
    @DisplayName("전송 사진 서른 장을 페이지로 읽고 삭제된 순번을 보존한다")
    void pagesThirtySentPhotosAndRetainsDeletedOrdinal() {
        var ids = new ArrayList<Long>();
        ids.add(photo.id());
        for (int index = 1; index < 30; index++) {
            ids.add(photo().id());
        }
        attachmentService.upload(
                owner,
                conversation.id(),
                "pending.gif",
                "image/gif",
                IMAGE.length,
                () -> new ByteArrayInputStream(IMAGE));
        attachmentCleaner.delete(photo, NOW);
        var page = service.list(owner, conversation.id(), null, 100);
        assertThat(page).hasSize(30);
        assertThat(page)
                .extracting(MediaObservationView::assetId)
                .containsExactlyElementsOf(ids.stream().map(Object::toString).toList());
        assertThat(page.getFirst().status()).isEqualTo(ObservationStatus.UNAVAILABLE);
        assertThat(page.getFirst().revision()).isNull();
        assertThat(page.getFirst().sourceFingerprint()).isNull();
        assertThat(page.get(1).status()).isEqualTo(ObservationStatus.NOT_ANALYZED);
        assertThat(page.get(1).revision()).isZero();
        assertThat(page.get(1).ordinal()).isEqualTo(2);
        assertThat(service.list(owner, conversation.id(), ids.get(19).toString(), 10))
                .extracting(MediaObservationView::ordinal)
                .containsExactly(21, 22, 23, 24, 25, 26, 27, 28, 29, 30);
        assertThat(observations.count()).isZero();
        assertThat(requests.count()).isZero();
    }

    @Test
    @DisplayName("읽기 전에 커서와 소유권을 검증한다")
    void validatesCursorAndOwnershipBeforeReading() {
        for (String cursor : List.of("0", "-1", "x", "999999999999999999999999", "999999")) {
            code(() -> service.list(owner, conversation.id(), cursor, 10), ErrorCode.VALIDATION_FAILED);
        }
        code(() -> service.list(owner, conversation.id(), null, 0), ErrorCode.VALIDATION_FAILED);
        code(() -> service.list(owner, conversation.id(), null, 101), ErrorCode.VALIDATION_FAILED);
        CurrentUser other = user();
        Conversation another = conversations.save(Conversation.startedBy(other.id(), "다른 대화", null, NOW));
        ChatAttachment otherPhoto = photo(other, another);
        code(
                () -> service.list(owner, conversation.id(), otherPhoto.id().toString(), 10),
                ErrorCode.CONVERSATION_NOT_FOUND);
        code(
                () -> service.record(owner, conversation.id(), otherPhoto.id(), 0, UUID.randomUUID(), input(), model()),
                ErrorCode.CONVERSATION_NOT_FOUND);
        code(() -> service.list(other, conversation.id(), "x", 10), ErrorCode.CONVERSATION_NOT_FOUND);
    }

    @Test
    @DisplayName("캐시 없이 실제 provenance와 처리 상태 전환을 저장한다")
    void persistsRealProvenanceAndProcessingTransitionsWithoutCache() {
        var processing = new MediaObservationInput(
                ObservationStatus.PROCESSING,
                null,
                List.of(),
                List.of(),
                new Coverage("ORIGINAL", null, null),
                null,
                null);
        MediaObservationView first = record(0, UUID.randomUUID(), processing, model());
        clock.advance(Duration.ofMinutes(15));
        assertThat(service.list(owner, conversation.id(), null, 10).getFirst().errorCode())
                .isEqualTo("ANALYSIS_STALE");
        assertThat(observations.count()).isEqualTo(1);
        var second = record(1, UUID.randomUUID(), input(), model());
        var third = record(2, UUID.randomUUID(), input(), changedModel());
        assertThat(first.revision()).isEqualTo(1);
        assertThat(second.revision()).isEqualTo(2);
        assertThat(third.revision()).isEqualTo(3);
        assertThat(third.provenance())
                .isEqualTo(new ObservationProvenance(
                        ObservationProvenanceKind.MODEL_RESULT,
                        123L,
                        "provider",
                        "provider-v2",
                        "model",
                        "model-v4",
                        1,
                        "media-observation-v1",
                        clock.instant()));
        assertThat(service.list(owner, conversation.id(), null, 10).getFirst()).isEqualTo(third);
        assertThat(requests.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("코드포인트와 UTF-8 본문 한도를 검사하고 부분 행을 남기지 않는다")
    void checksCodePointsAndUtf8BodyLimitWithoutPartialRows() {
        record(0, UUID.randomUUID(), input("😀".repeat(2000)), model());
        code(() -> record(1, UUID.randomUUID(), input("😀".repeat(2001)), model()), ErrorCode.VALIDATION_FAILED);
        var claims = new ArrayList<MediaObservationInput.Claim>();
        for (int index = 0; index < 50; index++) {
            claims.add(new MediaObservationInput.Claim("VISUAL", "😀".repeat(500), "CONFIRMED", List.of("근거")));
        }
        var oversized = new MediaObservationInput(
                ObservationStatus.SUCCEEDED,
                "관찰",
                claims,
                List.of(),
                new Coverage("ORIGINAL", null, null),
                input().evidence(),
                null);
        code(() -> record(1, UUID.randomUUID(), oversized, model()), ErrorCode.VALIDATION_FAILED);
        var nullClaim = new MediaObservationInput(
                ObservationStatus.SUCCEEDED,
                "관찰",
                Arrays.asList((MediaObservationInput.Claim) null),
                List.of(),
                new Coverage("ORIGINAL", null, null),
                input().evidence(),
                null);
        code(() -> record(1, UUID.randomUUID(), nullClaim, model()), ErrorCode.VALIDATION_FAILED);
        code(
                () -> record(
                        1,
                        UUID.randomUUID(),
                        input(),
                        new ObservationProvenance(
                                ObservationProvenanceKind.MODEL_RESULT,
                                123L,
                                null,
                                null,
                                "model",
                                null,
                                1,
                                "media-observation-v1",
                                null)),
                ErrorCode.VALIDATION_FAILED);
        var synthetic = new ObservationProvenance(
                ObservationProvenanceKind.SYNTHETIC_MEASUREMENT,
                null,
                null,
                null,
                null,
                null,
                1,
                "media-observation-v1",
                null);
        code(() -> record(1, UUID.randomUUID(), input(), synthetic), ErrorCode.VALIDATION_FAILED);
        assertThat(observations.count()).isEqualTo(1);
        assertThat(requests.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("잘못된 상태 coverage 근거를 거부한다")
    void rejectsInvalidStatusCoverageAndEvidence() {
        var crop = new Coverage("CROP", new MediaObservationInput.Region(0, 0, Double.NaN, 1), null);
        var invalidInputs = List.of(
                new MediaObservationInput(ObservationStatus.NOT_ANALYZED, null, null, null, null, null, null),
                new MediaObservationInput(ObservationStatus.UNAVAILABLE, null, null, null, null, null, null),
                new MediaObservationInput(ObservationStatus.FAILED, "본문", null, null, null, null, "MODEL_ERROR"),
                new MediaObservationInput(ObservationStatus.FAILED, null, null, null, null, null, "raw error"),
                new MediaObservationInput(
                        ObservationStatus.PARTIAL, "관찰", null, null, input().coverage(), input().evidence(), null),
                new MediaObservationInput(
                        ObservationStatus.SUCCEEDED,
                        "관찰",
                        null,
                        List.of("불확실"),
                        input().coverage(),
                        input().evidence(),
                        null),
                new MediaObservationInput(
                        ObservationStatus.SUCCEEDED, "관찰", null, null, crop, input().evidence(), null),
                new MediaObservationInput(
                        ObservationStatus.SUCCEEDED,
                        "관찰",
                        null,
                        null,
                        new Coverage("FIRST_FRAME", null, 1),
                        input().evidence(),
                        null),
                new MediaObservationInput(
                        ObservationStatus.SUCCEEDED,
                        "관찰",
                        null,
                        null,
                        input().coverage(),
                        new Evidence(ObservationProvenanceKind.MODEL_RESULT, "124"),
                        null));
        invalidInputs.forEach(
                value -> code(() -> record(0, UUID.randomUUID(), value, model()), ErrorCode.VALIDATION_FAILED));
        record(
                0,
                UUID.randomUUID(),
                new MediaObservationInput(ObservationStatus.FAILED, null, null, null, null, null, "MODEL_ERROR"),
                model());
        assertThat(requests.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 크기 원본 교체를 조회와 새 요청 재시도에서 발견한다")
    void detectsSameSizeSourceReplacementForListAndBothRequestKinds() throws IOException {
        UUID id = UUID.randomUUID();
        var initial = record(0, id, input(), model());
        byte[] changed = IMAGE.clone();
        changed[changed.length - 1]++;
        Files.write(file(photo), changed);
        var listed = service.list(owner, conversation.id(), null, 10).getFirst();
        assertThat(listed.status()).isEqualTo(ObservationStatus.NEEDS_REVIEW);
        assertThat(listed.errorCode()).isEqualTo("CONTENT_UNAVAILABLE");
        assertThat(listed.observation()).isNull();
        assertThat(listed.sourceFingerprint()).isNotEqualTo(initial.sourceFingerprint());
        assertThat(listed.revision()).isEqualTo(1);
        code(() -> record(1, UUID.randomUUID(), input(), model()), ErrorCode.MEDIA_OBSERVATION_CONFLICT);
        code(() -> record(0, id, input(), model()), ErrorCode.MEDIA_OBSERVATION_CONFLICT);
        assertThat(observations.count()).isEqualTo(1);
        assertThat(requests.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("소유자 변경과 업로더 불일치를 alias 재시도에도 차단한다")
    void blocksOwnerChangesAndUploaderMismatchIncludingAliasRetry() {
        UUID request = UUID.randomUUID();
        record(0, request, input(), model());
        CurrentUser other = user();
        jdbc.update("update conversation set user_id=? where id=?", other.id(), conversation.id());
        code(() -> record(0, request, input(), model()), ErrorCode.CONVERSATION_NOT_FOUND);
        code(
                () -> service.record(other, conversation.id(), photo.id(), 0, request, input(), model()),
                ErrorCode.CONVERSATION_NOT_FOUND);
        assertThat(service.list(other, conversation.id(), null, 10).getFirst().observation())
                .isNull();
        assertThat(service.list(other, conversation.id(), null, 10).getFirst().provenance())
                .isNull();
        jdbc.update("update conversation set user_id=? where id=?", owner.id(), conversation.id());
        jdbc.update("update media_observation set owner_user_id=?", other.id());
        code(() -> record(0, request, input(), model()), ErrorCode.CONVERSATION_NOT_FOUND);
        assertThat(service.list(owner, conversation.id(), null, 10).getFirst().status())
                .isEqualTo(ObservationStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("성공 IO 크기 만료 SQL 실패에서 원본 스트림을 닫는다")
    void closesOriginalOnSuccessIoSizeExpiryAndSqlFailure() {
        AtomicBoolean closed = new AtomicBoolean();
        var tracked = trackingStore(closed, 0);
        var reading = local(tracked, bodies, observations, requests, attachments, access);
        reading.record(owner, conversation.id(), photo.id(), 0, UUID.randomUUID(), input(), model());
        assertThat(closed).isTrue();
        for (int mode = 1; mode <= 3; mode++) {
            closed.set(false);
            var failing = local(trackingStore(closed, mode), bodies, observations, requests, attachments, access);
            code(
                    () -> failing.record(owner, conversation.id(), photo.id(), 1, UUID.randomUUID(), input(), model()),
                    ErrorCode.ATTACHMENT_GONE);
            assertThat(closed).isTrue();
        }
        clock.set(NOW);
        closed.set(false);
        var broken =
                fail(MediaObservationRepository.class, observations, "findFirstByAttachmentIdOrderByRevisionDesc", 1);
        assertThatThrownBy(() -> local(tracked, bodies, broken, requests, attachments, access)
                        .record(owner, conversation.id(), photo.id(), 1, UUID.randomUUID(), input(), model()))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(closed).isTrue();
        assertThat(observations.count()).isEqualTo(1);
    }

    private AttachmentStore trackingStore(AtomicBoolean closed, int mode) {
        return new AttachmentStore(properties) {
            @Override
            public InputStream open(ChatAttachment attachment) {
                closed.set(false);
                return new FilterInputStream(new ByteArrayInputStream(mode == 2 ? new byte[1] : IMAGE)) {
                    @Override
                    public int read(byte[] buffer, int offset, int length) throws IOException {
                        if (mode == 1) {
                            throw new IOException("synthetic read failure");
                        }
                        if (mode == 3) {
                            clock.advance(Duration.ofDays(30));
                        }
                        return super.read(buffer, offset, length);
                    }

                    @Override
                    public void close() throws IOException {
                        closed.set(true);
                        super.close();
                    }
                };
            }
        };
    }

    @Test
    @DisplayName("조회 최종 SQL 실패를 전파하기 전에 스트림을 닫는다")
    void closesStreamBeforePropagatingFinalListSqlFailure() {
        record(0, UUID.randomUUID(), input(), model());
        AtomicBoolean closed = new AtomicBoolean();
        var broken = fail(
                ChatAttachmentRepository.class,
                attachments,
                "existsByIdAndConversationIdAndUploadedByUserIdAndMessageIdIsNotNullAndDeletedAtIsNullAndExpiresAtAfter",
                3);
        assertThatThrownBy(() -> local(trackingStore(closed, 0), bodies, observations, requests, broken, access)
                        .list(owner, conversation.id(), null, 10))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(closed).isTrue();
        assertThat(observations.count()).isEqualTo(1);
        assertThat(requests.count()).isEqualTo(1);
    }
}

/** 같은 실제 빈과 합성 첨부를 쓰며 테스트별 Spring 설정을 추가하지 않는다. */
@BackendIntegrationTest
abstract class ObservationFixture {
    static final Instant NOW = Instant.parse("2026-10-10T01:00:00Z");
    static final byte[] IMAGE = {'G', 'I', 'F', '8', '9', 'a', 1, 2, 3};

    @Autowired
    MediaObservationService service;

    @Autowired
    MediaObservationRepository observations;

    @Autowired
    MediaObservationRequestRepository requests;

    @Autowired
    MediaObservationBodies bodies;

    @Autowired
    MediaObservationCleaner observationCleaner;

    @Autowired
    AttachmentCleaner attachmentCleaner;

    @Autowired
    AttachmentService attachmentService;

    @Autowired
    ChatAttachmentRepository attachments;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ConversationAccess access;

    @Autowired
    ChatContentMutationCoordinator mutations;

    @Autowired
    AttachmentStore store;

    @Autowired
    AttachmentProperties properties;

    @Autowired
    TestClock clock;

    @Autowired
    ObjectMapper json;

    @Autowired
    TextCipher cipher;

    @Autowired
    AppUserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager manager;

    CurrentUser owner;
    Conversation conversation;
    ChatAttachment photo;
    private final List<Path> directories = new ArrayList<>();

    @BeforeEach
    void prepare() {
        clock.set(NOW);
        requests.deleteAll();
        observations.deleteAll();
        attachments.deleteAll();
        owner = user();
        conversation = conversations.save(Conversation.startedBy(owner.id(), "관찰 회귀", null, NOW));
        photo = photo();
    }

    @AfterEach
    void cleanup() throws IOException {
        for (Path directory : directories) {
            if (Files.exists(directory)) {
                try (var paths = Files.walk(directory)) {
                    for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(file);
                    }
                }
            }
        }
    }

    CurrentUser user() {
        AppUser row = users.save(AppUser.of(UUID.randomUUID() + "@example.test", "주인", 1L, UserRole.MEMBER, NOW));
        Path directory = Path.of(properties.root(), "users", AttachmentStore.userDirectoryKey(row.id()));
        if (Files.exists(directory)) {
            try (var paths = Files.walk(directory)) {
                for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        directories.add(directory);
        return new CurrentUser(row.id(), row.email(), row.displayName(), row.groupId(), row.role());
    }

    ChatAttachment photo() {
        return photo(owner, conversation);
    }

    ChatAttachment photo(CurrentUser user, Conversation target) {
        var row = attachmentService.upload(
                user, target.id(), "a.gif", "image/gif", IMAGE.length, () -> new ByteArrayInputStream(IMAGE));
        attachmentService.attach(100L + row.id(), target.id(), List.of(row.id()));
        return attachments.findById(row.id()).orElseThrow();
    }

    Path file(ChatAttachment row) {
        return Path.of(
                properties.root(),
                "users",
                AttachmentStore.userDirectoryKey(row.uploadedByUserId()),
                row.conversationId().toString(),
                row.storedName());
    }

    MediaObservationView record(
            long revision, UUID request, MediaObservationInput input, ObservationProvenance source) {
        return service.record(owner, conversation.id(), photo.id(), revision, request, input, source);
    }

    MediaObservationService local(
            AttachmentStore localStore,
            MediaObservationBodies localBodies,
            MediaObservationRepository localRows,
            MediaObservationRequestRepository localRequests,
            ChatAttachmentRepository localAttachments,
            ConversationAccess localAccess) {
        return new MediaObservationService(
                localAccess,
                localAttachments,
                localStore,
                localRows,
                localRequests,
                localBodies,
                mutations,
                json,
                clock);
    }

    static MediaObservationInput input() {
        return input("관찰 표식");
    }

    static MediaObservationInput input(String summary) {
        return new MediaObservationInput(
                ObservationStatus.SUCCEEDED,
                summary,
                List.of(),
                List.of(),
                new Coverage("ORIGINAL", null, null),
                new Evidence(ObservationProvenanceKind.MODEL_RESULT, "123"),
                null);
    }

    static ObservationProvenance model() {
        return new ObservationProvenance(
                ObservationProvenanceKind.MODEL_RESULT,
                123L,
                "provider",
                "provider-v2",
                "model",
                "model-v3",
                1,
                "media-observation-v1",
                Instant.EPOCH);
    }

    static ObservationProvenance changedModel() {
        var source = model();
        return new ObservationProvenance(
                source.kind(),
                source.executionId(),
                source.provider(),
                source.providerVersion(),
                source.model(),
                "model-v4",
                source.schemaVersion(),
                source.promptVersion(),
                source.observedAt());
    }

    static void code(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(expected));
    }

    static <T> T fail(Class<T> type, T delegate, String failingMethod, int failingCall) {
        AtomicInteger calls = new AtomicInteger();
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            if (method.getName().equals(failingMethod) && calls.incrementAndGet() == failingCall) {
                throw new DataAccessResourceFailureException("synthetic SQL failure");
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException ex) {
                throw ex.getCause();
            }
        }));
    }
}
