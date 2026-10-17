package com.bifos.assistant.chat;

import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.HexFormat;
import com.bifos.assistant.connector.application.ConnectorCatalogCache;
import com.bifos.assistant.chat.domain.ContentDraftException;
import java.lang.reflect.InvocationTargetException;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;

import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.application.model.ContentDraftSnapshotView;
import com.bifos.assistant.chat.application.model.ChatContentMutationTarget;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.transaction.IllegalTransactionStateException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ContentDraftSnapshotHash;
import com.bifos.assistant.chat.application.ContentDraftSnapshots;
import com.bifos.assistant.chat.application.ContentDraftSnapshotInputs;
import com.bifos.assistant.chat.application.ContentDraftBindingAccess;
import com.bifos.assistant.chat.infra.ContentDraftJobRepository;
import com.bifos.assistant.chat.infra.ContentDraftSnapshotRepository;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.hermes.infra.HttpContentDraftSnapshotClient;
import com.bifos.assistant.hermes.config.HermesProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorSnapshotDeclaration;
import com.bifos.assistant.people.application.PersonAccessService;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class ContentDraftSnapshotsTest extends ContentDraftFixture {
    @Autowired
    ContentDraftSnapshots snapshots;
    @Autowired
    ContentDraftSnapshotInputs snapshotInputs;
    @Autowired
    ContentDraftBindingAccess bindingAccess;
    @Autowired
    ContentDraftSnapshotRepository snapshotRows;
    @Autowired
    ContentDraftJobRepository jobRows;
    @Autowired
    AgentRepository agents;
    @Autowired
    AgentExecutionRepository executions;
    @Autowired
    AllowedPersonRepository people;
    @Autowired
    PersonAccessService personAccess;
    @Autowired
    ConnectorConnectionRepository connections;
    @Autowired
    ConnectorBindingRepository bindings;
    @Autowired
    HermesConnectorClient catalog;
    @Autowired
    HttpContentDraftSnapshotClient compiler;
    @Autowired
    ConnectorCatalogCache cache;
    ConnectorBinding binding;
    AgentExecution origin;
    AllowedPerson person;
    UUID draftId;


    @BeforeEach
    void prepareSnapshot() {
        snapshotRows.deleteAll();
        jobRows.deleteAll();
        String profile = "preview-" + UUID.randomUUID();
        person = people.save(AllowedPerson.of(owner.email(), "주인", profile));
        var agent = agents.save(Agent.of(profile, "preview", profile, "http://localhost", CostMode.SUBSCRIPTION,
            CredentialScope.DEDICATED, AgentVisibility.PRIVATE, owner.id()));
        var connection = ConnectorConnection.pending(owner.id(), "snapshot-test", NOW);
        connection.ready(NOW);
        connection = connections.save(connection);
        binding = ConnectorBinding.pending(agent, connection, "snapshot-test", NOW);
        binding.installed(false, NOW);
        binding.ready(NOW);
        binding = bindings.save(binding);
        origin = executions.save(AgentExecution.builder().userId(owner.id()).conversationId(conversation.id()).agentId(agent.id())
            .profileName(profile).costMode(CostMode.SUBSCRIPTION).status(ExecutionStatus.RUNNING).startedAt(NOW).build());
        var declaration = new ConnectorSnapshotDeclaration("content-snapshot-v1", "compile_snapshot", "start_snapshot", "status_snapshot",
            Map.of("compile", true, "start", false, "status", false));
        when(catalog.readCatalog()).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return List.of(new ConnectorManifest("snapshot-test", "검사", "", List.of(), "verify", "snapshot-test",
                1, List.of(), List.of(), null, false, false, null, null, null, declaration));
        });
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return ContentDraftSnapshotHash.compile(call.getArgument(2), call.getArgument(3));
        }).when(compiler).compile(any(), any(), any(), any());
        draftId = drafts.create(owner, conversation.id(), inputDraft("비밀 원고 😀", true)).draft().draftId();
    }


    @AfterEach
    void removeSnapshots() {
        jobRows.deleteAll();
        snapshotRows.deleteAll();
        bindings.deleteById(binding.id());
        connections.deleteById(binding.connection().id());
        executions.deleteById(origin.id());
        jdbc.update("delete from agent_memory_collection where agent_id=?", binding.agent().id());
        agents.deleteById(binding.agent().id());
        people.deleteById(person.id());
    }


    @Test
    @DisplayName("REST long revision과 HTTP 정수 응답은 실제 Spring 저장 경로에서 같은 tuple이다")
    void acceptsRealHttpWireNumbersForRestDraft() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/connectors/snapshot-test/snapshot", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer synthetic-token");
            var request = json.readTree(exchange.getRequestBody().readAllBytes());
            var result = ContentDraftSnapshotHash.compile(request.path("args").path("canonicalDraft"), request.path("args").path("resolvedAssets"));
            var body = json.createObjectNode().put("ok", true).set("result", result);
            body.set("capabilities", json.createObjectNode().put("compile", true).put("start", false).put("status", false));
            byte[] bytes = json.writeValueAsBytes(body);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var http = new HttpContentDraftSnapshotClient(new HermesProperties(null, "http://127.0.0.1:" + server.getAddress().getPort(),
                "synthetic-token", null, null, null, null, null));
            var real = new ContentDraftSnapshots(access, mutations, snapshotInputs, bindingAccess, snapshotRows, revisionRows, draftRows, http, json, clock, attachments);
            var accepted = real.preview(owner, conversation.id(), draftId, 1, binding.id(), origin.id());
            assertThat(accepted.preview().revision()).isEqualTo(1L);
            assertThat(snapshotRows.count()).isEqualTo(1);
            assertThat(preview().snapshotId()).isEqualTo(accepted.snapshotId());
        } finally {
            server.stop(0);
        }
    }


    @Test
    @DisplayName("REST 원고의 반복 preview는 같은 snapshot과 암호화 본문 한 곳을 보존한다")
    void reusesSnapshotAndKeepsOnlyEncryptedRevisionBody() {
        var first = preview();
        assertThat(preview().snapshotId()).isEqualTo(first.snapshotId());
        assertThat(snapshots.readCurrentSnapshot(owner, conversation.id(), first.snapshotId()).preview().title()).isEqualTo("비밀 원고 😀");
        assertThat(first.assetIds()).containsExactly(photo.id().toString(), secondPhoto.id().toString());
        assertThat(json.writeValueAsString(first)).doesNotContain("photo_dir", "fileName", "storedName", "payload", "authorizationFingerprint", properties.agentRoot());
        assertThat(snapshotRows.count()).isEqualTo(1);
        assertThat(jobRows.count()).isZero();
        assertThat(revisionRows.findAll()).allSatisfy(row -> assertThat(row.body()).startsWith("v1.").doesNotContain("비밀"));
    }


    @Test
    @DisplayName("규칙 전에 저장한 반복 사진 판의 preview는 일시 장애가 아닌 중복 사진 코드로 커넥터 호출 전에 멈춘다")
    void rejectsLegacyDuplicatePhotoRevisionBeforeCompiler() {
        var draft = draftRows.findAll().getFirst();
        var row = revisionRows.findAll().getFirst();
        var legacy = json.createObjectNode().put("title", "옛 원고").put("category", "분류");
        legacy.putArray("tags");
        var blocks = legacy.putArray("blocks");
        blocks.addObject().put("type", "image").put("assetId", photo.id().toString());
        blocks.addObject().put("type", "image").put("assetId", photo.id().toString());
        var sealed = draftBodies.seal(draft, 1, json.writeValueAsString(legacy));
        jdbc.update("update content_draft_revision set body=?, body_key_id=? where id=?", sealed.content(), sealed.keyId(), row.id());
        code(this::preview, ErrorCode.CONTENT_DRAFT_DUPLICATE_PHOTO);
        assertThat(snapshotRows.count()).isZero();
    }


    @Test
    @DisplayName("compiler를 기다리는 동안 실제 revision 수정이 커밋되면 snapshot을 남기지 않는다")
    void rejectsRevisionCommittedDuringCompiler() throws Exception {
        race(() -> drafts.revise(owner, conversation.id(), draftId, 1, inputDraft("수정", false)), ErrorCode.CONTENT_DRAFT_STALE);
    }


    @Test
    @DisplayName("compiler를 기다리는 동안 실제 허용 목록 철회가 커밋되면 snapshot을 남기지 않는다")
    void rejectsPermissionRevokedDuringCompiler() throws Exception {
        race(() -> personAccess.setEnabled(person.id(), false), ErrorCode.CONTENT_DRAFT_UNAVAILABLE);
    }


    @Test
    @DisplayName("compiler를 기다리는 동안 원본 byte를 교체하면 snapshot을 남기지 않는다")
    void rejectsReplacedOriginalDuringCompiler() throws Exception {
        race(() -> {
            try {
                var changed = IMAGE.clone();
                changed[8]++;
                Files.write(file(photo), changed);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }, ErrorCode.CONTENT_DRAFT_STALE);
    }


    @Test
    @DisplayName("두 동시 preview가 같은 owner snapshot을 반환한다")
    void deduplicatesConcurrentPreviews() throws Exception {
        var ready = new CountDownLatch(2);
        doAnswer(call -> {
            ready.countDown();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            return ContentDraftSnapshotHash.compile(call.getArgument(2), call.getArgument(3));
        }).when(compiler).compile(any(), any(), any(), any());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(this::preview);
            var second = pool.submit(this::preview);
            assertThat(first.get(10, TimeUnit.SECONDS).snapshotId()).isEqualTo(second.get(10, TimeUnit.SECONDS).snapshotId());
        }
        assertThat(snapshotRows.count()).isEqualTo(1);
    }


    @Test
    @DisplayName("owner와 대화 ACL을 UUID와 해시보다 먼저 검사한다")
    void checksOwnerAndConversationBeforeSnapshot() {
        var row = preview();
        var other = user();
        code(() -> snapshots.readCurrentSnapshot(other, conversation.id(), row.snapshotId()), ErrorCode.CONVERSATION_NOT_FOUND);
        var another = conversations.save(Conversation.startedBy(owner.id(), "다른 대화", null, NOW));
        code(() -> snapshots.readCurrentSnapshot(owner, another.id(), row.snapshotId()), ErrorCode.CONVERSATION_NOT_FOUND);
        assertThat(snapshotRows.count()).isEqualTo(1);
    }


    @Test
    @DisplayName("삭제 요청 만료 미전송 타대화와 참조 누락은 snapshot을 거절한다")
    void rejectsUnavailableAndMismatchedAssets() {
        var another = conversations.save(Conversation.startedBy(owner.id(), "다른 대화", null, NOW));
        for (String mutation : List.of("deletion_requested_at=CURRENT_TIMESTAMP", "expires_at='2000-01-01 00:00:00'", "message_id=null", "conversation_id=" + another.id())) {
            jdbc.update("update chat_attachment set " + mutation + " where id=?", photo.id());
            try {
                code(this::preview, ErrorCode.CONTENT_DRAFT_STALE);
            } finally {
                jdbc.update("update chat_attachment set deletion_requested_at=null,expires_at=?,message_id=?,conversation_id=? where id=?",
                    photo.expiresAt(), photo.messageId(), conversation.id(), photo.id());
            }
        }
        jdbc.update("delete from content_draft_asset where revision_id=? and position=1", revisionRows.findAll().getFirst().id());
        code(this::preview, ErrorCode.CONTENT_DRAFT_STALE);
        assertThat(snapshotRows.count()).isZero();
    }


    @Test
    @DisplayName("실제 UNIQUE 실패 트랜잭션 뒤 새 owner 범위 읽기로 동일 snapshot을 찾는다")
    void retriesOwnerReadAfterRolledBackUniqueFailure() {
        var first = preview();
        var hidden = new AtomicBoolean();
        var repository = (ContentDraftSnapshotRepository)Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[] {ContentDraftSnapshotRepository.class}, (proxy, method, args) -> {
                if (method.getName().startsWith("findByRevisionId") && hidden.compareAndSet(false, true)) {
                    return Optional.empty();
                }
                try {
                    return method.invoke(snapshotRows, args);
                } catch (InvocationTargetException ex) {
                    throw ex.getCause();
                }
            });
        var racing = new ContentDraftSnapshots(access, mutations, snapshotInputs, bindingAccess, repository, revisionRows, draftRows, compiler, json, clock, attachments);
        assertThat(racing.preview(owner, conversation.id(), draftId, 1, binding.id(), origin.id()).snapshotId()).isEqualTo(first.snapshotId());
        assertThat(snapshotRows.count()).isEqualTo(1);
        assertThat(preview().snapshotId()).isEqualTo(first.snapshotId());
    }


    @Test
    @DisplayName("고정 관찰 대응을 다시 검증하고 중복 첨부 입력과 변조 compiler tuple을 거절한다")
    void revalidatesObservationAndRejectsCompilerDuplicateAssets() {
        record(0, UUID.randomUUID(), input(), model());
        var observation = observations.findAll().getFirst();
        var node = json.valueToTree(inputDraft("관찰", true));
        ((ObjectNode)node.path("blocks").get(0)).set("observationRef",
            json.createObjectNode().put("id", observation.id().toString()).put("revision", observation.revision()));
        draftId = drafts.create(owner, conversation.id(), reader.read(node)).draft().draftId();
        var first = preview();
        jdbc.update("update media_observation set source_fingerprint=? where id=?", "f".repeat(64), observation.id());
        code(() -> snapshots.readCurrentSnapshot(owner, conversation.id(), first.snapshotId()), ErrorCode.CONTENT_DRAFT_STALE);
        jdbc.update("update media_observation set source_fingerprint=? where id=?", observation.sourceFingerprint(), observation.id());
        doAnswer(call -> {
            var result = ContentDraftSnapshotHash.compile(call.getArgument(2), call.getArgument(3));
            ((ArrayNode)result.path("assetManifest")).add(result.path("assetManifest").get(0));
            return result;
        }).when(compiler).compile(any(), any(), any(), any());
        code(this::preview, ErrorCode.CONTENT_DRAFT_UNAVAILABLE);
        assertThat(snapshotRows.count()).isEqualTo(1);
    }


    @Test
    @DisplayName("독립 진입과 MANDATORY 참가를 혼용하면 실패한다")
    void enforcesParticipantAndSingleCoordinatorEntry() {
        var row = preview();
        assertThatThrownBy(() -> snapshots.requireCurrentSnapshotParticipant(owner, conversation.id(), row.snapshotId()))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(status -> snapshots.readCurrentSnapshot(owner, conversation.id(), row.snapshotId())))
            .isInstanceOf(IllegalStateException.class);
        var input = mutations.run(owner.id(), new ChatContentMutationTarget(conversation.id(), List.of()),
            () -> snapshots.requireCurrentSnapshotParticipant(owner, conversation.id(), row.snapshotId()));
        assertThat(input.toString()).isEqualTo("ContentDraftSnapshotInput[redacted]");
    }


    @Test
    @DisplayName("binding 해제와 만료 뒤에도 기존 snapshot 이력은 남는다")
    void keepsHistoryAfterBindingRemovalAndExpiry() {
        var row = preview();
        jdbc.update("update agent_connector_binding set desired_enabled=false where id=?", binding.id());
        code(() -> snapshots.readCurrentSnapshot(owner, conversation.id(), row.snapshotId()), ErrorCode.CONTENT_DRAFT_UNAVAILABLE);
        jdbc.update("update agent_connector_binding set desired_enabled=true where id=?", binding.id());
        clock.set(row.expiresAt());
        code(() -> snapshots.readCurrentSnapshot(owner, conversation.id(), row.snapshotId()), ErrorCode.CONTENT_DRAFT_GONE);
        assertThat(snapshotRows.count()).isEqualTo(1);
    }


    @Test
    @DisplayName("유효 binding 판 변경은 옛 행을 보존하고 같은 revision에 새 preview를 만든다")
    void recoversAfterBindingStampChange() {
        recoversAfterChange(() -> jdbc.update("update agent_connector_binding set updated_at=? where id=?", NOW.plusSeconds(1), binding.id()),
            ErrorCode.CONTENT_DRAFT_UNAVAILABLE);
    }


    @Test
    @DisplayName("유효 connection 판 변경은 옛 행을 보존하고 같은 revision에 새 preview를 만든다")
    void recoversAfterConnectionStampChange() {
        recoversAfterChange(() -> jdbc.update("update connector_connection set updated_at=? where id=?", NOW.plusSeconds(1), binding.connection().id()),
            ErrorCode.CONTENT_DRAFT_UNAVAILABLE);
    }


    @Test
    @DisplayName("catalog 정책 변경 뒤 참가 호출자는 밖에서 준비한 stamp를 명시 대조한다")
    void recoversAfterPolicyChangeAndRequiresCallerComparison() throws Exception {
        var first = preview();
        var prepared = bindingAccess.prepare(owner, binding.agent().id(), binding.id());
        when(catalog.readCatalog()).thenReturn(List.of(new ConnectorManifest("snapshot-test", "검사", "", List.of(), "verify", "snapshot-test",
            1, List.of(), List.of(), null, false, false, null, null, null,
            new ConnectorSnapshotDeclaration("content-snapshot-v1", "compile_changed", "start_snapshot", "status_snapshot",
                Map.of("compile", true, "start", false, "status", false)))));
        var field = cache.getClass().getDeclaredField("readAt");
        field.setAccessible(true);
        field.set(cache, null);
        var fresh = bindingAccess.prepare(owner, binding.agent().id(), binding.id());
        var input = mutations.run(owner.id(), new ChatContentMutationTarget(conversation.id(), List.of()),
            () -> snapshots.requireCurrentSnapshotParticipant(owner, conversation.id(), first.snapshotId()));
        assertThat(input.binding()).isEqualTo(prepared).isNotEqualTo(fresh);
        assertThatThrownBy(() -> {
            if (!fresh.equals(input.binding())) {
                throw ContentDraftSnapshotInputs.stale();
            }
        }).isInstanceOf(ContentDraftException.class);
        assertRecovered(first, ErrorCode.CONTENT_DRAFT_STALE);
    }


    @Test
    @DisplayName("새 유효 origin은 새 UUID를 만들고 취소된 origin은 계속 거절한다")
    void recoversWithNewOriginAfterCancellation() {
        var first = preview();
        var old = history(first.snapshotId());
        var next = executions.save(AgentExecution.builder().userId(owner.id()).conversationId(conversation.id()).agentId(binding.agent().id())
            .profileName(origin.profileName()).costMode(CostMode.SUBSCRIPTION).status(ExecutionStatus.RUNNING).startedAt(NOW.plusSeconds(1)).build());
        try {
            jdbc.update("update agent_execution set status='CANCELLED' where id=?", origin.id());
            code(this::preview, ErrorCode.CONTENT_DRAFT_UNAVAILABLE);
            code(() -> snapshots.readCurrentSnapshot(owner, conversation.id(), first.snapshotId()), ErrorCode.CONTENT_DRAFT_UNAVAILABLE);
            var second = snapshots.preview(owner, conversation.id(), draftId, 1, binding.id(), next.id());
            assertThat(second.snapshotId()).isNotEqualTo(first.snapshotId());
            assertThat(second.snapshotHash()).isEqualTo(first.snapshotHash());
            assertThat(snapshots.preview(owner, conversation.id(), draftId, 1, binding.id(), next.id()).snapshotId()).isEqualTo(second.snapshotId());
            assertThat(snapshots.readCurrentSnapshot(owner, conversation.id(), second.snapshotId())).isEqualTo(second);
            assertThat(snapshotRows.count()).isEqualTo(2);
            assertThat(history(first.snapshotId())).isEqualTo(old);
        } finally {
            executions.deleteById(next.id());
        }
    }


    @Test
    @DisplayName("저장된 권한 지문 변조는 실제 캡처 값 재계산에서 거절한다")
    void rejectsTamperedAuthorizationFingerprint() {
        var first = preview();
        for (String field : List.of("origin_execution_id", "agent_id")) {
            jdbc.update("update content_draft_snapshot set " + field + "=" + field + "+10000");
            try {
                code(this::preview, ErrorCode.CONTENT_DRAFT_STALE);
            } finally {
                jdbc.update("update content_draft_snapshot set " + field + "=" + field + "-10000");
            }
        }
        jdbc.update("update content_draft_snapshot set authorization_fingerprint=?", "f".repeat(64));
        code(() -> snapshots.readCurrentSnapshot(owner, conversation.id(), first.snapshotId()), ErrorCode.CONTENT_DRAFT_STALE);
        assertThat(snapshotRows.count()).isEqualTo(1);
    }


    private void recoversAfterChange(Runnable change, ErrorCode oldError) {
        var first = preview();
        change.run();
        assertRecovered(first, oldError);
    }


    private void assertRecovered(ContentDraftSnapshotView first, ErrorCode oldError) {
        var old = history(first.snapshotId());
        code(() -> snapshots.readCurrentSnapshot(owner, conversation.id(), first.snapshotId()), oldError);
        var second = preview();
        assertThat(second.snapshotId()).isNotEqualTo(first.snapshotId());
        assertThat(second.snapshotHash()).isEqualTo(first.snapshotHash());
        assertThat(preview().snapshotId()).isEqualTo(second.snapshotId());
        assertThat(snapshots.readCurrentSnapshot(owner, conversation.id(), second.snapshotId())).isEqualTo(second);
        assertThat(snapshotRows.count()).isEqualTo(2);
        assertThat(history(first.snapshotId())).isEqualTo(old);
        code(() -> snapshots.readCurrentSnapshot(owner, conversation.id(), first.snapshotId()), oldError);
    }


    private Map<String, Object> history(UUID snapshotId) {
        var id = snapshotRows.findByPublicIdAndOwnerUserIdAndConversationId(snapshotId, owner.id(), conversation.id()).orElseThrow().id();
        var values = jdbc.queryForMap("select * from content_draft_snapshot where id=?", id);
        values.replaceAll((key, value) -> value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value);
        return values;
    }


    private ContentDraftSnapshotView preview() {
        return snapshots.preview(owner, conversation.id(), draftId, 1, binding.id(), origin.id());
    }


    private void race(Runnable change, ErrorCode expected) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return ContentDraftSnapshotHash.compile(call.getArgument(2), call.getArgument(3));
        }).when(compiler).compile(any(), any(), any(), any());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = pool.submit(() -> code(this::preview, expected));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                change.run();
            } finally {
                release.countDown();
            }
            pending.get(10, TimeUnit.SECONDS);
        }
        assertThat(snapshotRows.count()).isZero();
    }
}
