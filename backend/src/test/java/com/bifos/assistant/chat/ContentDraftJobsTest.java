package com.bifos.assistant.chat;

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
import com.bifos.assistant.chat.application.ContentDraftJobQueries;
import com.bifos.assistant.chat.application.ContentDraftSaveRequests;
import com.bifos.assistant.chat.application.ContentDraftSnapshotHash;
import com.bifos.assistant.chat.application.ContentDraftSnapshots;
import com.bifos.assistant.chat.application.model.ContentDraftSaveRequestView;
import com.bifos.assistant.chat.domain.type.ContentDraftJobStatus;
import com.bifos.assistant.chat.infra.ContentDraftJobRepository;
import com.bifos.assistant.chat.infra.ContentDraftSnapshotRepository;
import com.bifos.assistant.connector.application.ConnectorActionService;
import com.bifos.assistant.connector.application.ConnectorBindingService;
import com.bifos.assistant.connector.application.ConnectorCatalogCache;
import com.bifos.assistant.connector.application.ContentDraftJobAuthorizer;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.domain.type.GrantPeriod;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.HttpContentDraftSnapshotClient;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.hermes.dto.ConnectorSnapshotDeclaration;
import com.bifos.assistant.hermes.dto.ConnectorTool;
import com.bifos.assistant.people.application.PersonAccessService;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.RecordingContentDraftJobStarter;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.support.TransactionTemplate;

/** 원고 저장 요청, 승인과 jobId 예약, worker 허가를 실제 Spring 트랜잭션과 저장소로 확인한다. */
class ContentDraftJobsTest extends ContentDraftFixture {
    private static final String CONNECTOR = "snapshot-test";

    @Autowired
    ContentDraftSnapshots snapshots;
    @Autowired
    ContentDraftSaveRequests saves;
    @Autowired
    ContentDraftJobQueries jobQueries;
    @Autowired
    ContentDraftJobAuthorizer authorizer;
    @Autowired
    ConnectorActionService approvals;
    @Autowired
    ConnectorBindingService bindingService;
    @Autowired
    ConnectorActionRepository actionRows;
    @Autowired
    ContentDraftSnapshotRepository snapshotRows;
    @Autowired
    ContentDraftJobRepository jobRows;
    @Autowired
    AgentRepository agents;
    @Autowired
    AgentExecutionRepository executions;
    @Autowired
    ExecutionRecorder recorder;
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
    @Autowired
    RecordingContentDraftJobStarter starter;
    @LocalServerPort
    int port;

    ConnectorBinding binding;
    AgentExecution origin;
    AllowedPerson person;
    UUID draftId;

    @BeforeEach
    void prepareSave() throws Exception {
        jobRows.deleteAll();
        snapshotRows.deleteAll();
        String profile = "save-" + UUID.randomUUID().toString().substring(0, 8);
        person = people.save(AllowedPerson.of(owner.email(), "주인", profile, NOW));
        var agent = agents.save(Agent.of(profile, "save", profile, "http://localhost", CostMode.SUBSCRIPTION,
            CredentialScope.DEDICATED, AgentVisibility.PRIVATE, owner.id(), NOW));
        var connection = ConnectorConnection.pending(owner.id(), CONNECTOR, NOW);
        connection.ready(NOW);
        connection = connections.save(connection);
        binding = ConnectorBinding.pending(agent, connection, CONNECTOR, NOW);
        binding.installed(false, NOW);
        binding.ready(NOW);
        binding = bindings.save(binding);
        origin = executions.save(AgentExecution.builder().userId(owner.id()).conversationId(conversation.id()).agentId(agent.id())
            .profileName(profile).costMode(CostMode.SUBSCRIPTION).status(ExecutionStatus.RUNNING).startedAt(NOW).build());
        catalog(true);
        doAnswer(call -> ContentDraftSnapshotHash.compile(call.getArgument(2), call.getArgument(3)))
            .when(compiler).compile(any(), any(), any(), any());
        draftId = drafts.create(owner, conversation.id(), inputDraft("비밀 원고", true)).draft().draftId();
        starter.answer(call -> Optional.empty());
    }

    @AfterEach
    void removeSave() {
        jobRows.deleteAll();
        snapshotRows.deleteAll();
        actionRows.deleteAll();
        bindings.deleteById(binding.id());
        connections.deleteById(binding.connection().id());
        executions.deleteById(origin.id());
        jdbc.update("delete from agent_memory_collection where agent_id=?", binding.agent().id());
        agents.deleteById(binding.agent().id());
        people.deleteById(person.id());
    }

    @Test
    @DisplayName("같은 snapshot의 반복·동시 저장 요청은 상시 허락 없는 승인 줄 하나와 job 하나만 만든다")
    void createsOneApprovalLinePerSnapshotForRepeatedAndConcurrentRequests() throws Exception {
        UUID snapshotId = preview();
        var results = new ArrayList<ContentDraftSaveRequestView>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var pending = List.of(1, 2, 3).stream().map(i -> pool.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return saves.requestSave(owner, origin.id(), snapshotId);
            })).toList();
            start.countDown();
            for (var future : pending) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
        }
        assertThat(results).extracting(ContentDraftSaveRequestView::actionId).containsOnly(results.getFirst().actionId());
        assertThat(results).allSatisfy(view -> {
            assertThat(view.jobId()).isNull();
            assertThat(view.status()).isEqualTo(ContentDraftJobStatus.PREVIEW_READY);
        });
        assertThat(actionRows.count()).isEqualTo(1);
        assertThat(jobRows.count()).isEqualTo(1);
        var action = actionRows.findAll().getFirst();
        assertThat(action.status()).isEqualTo(ActionStatus.PENDING);
        assertThat(action.toolName()).isEqualTo("save_draft");
        assertThat(action.argsJson()).isEqualTo("{\"snapshotId\":\"" + snapshotId + "\"}");
        var card = approvals.listForConversation(owner, conversation.id()).getFirst();
        assertThat(card.grantAllowed()).isFalse();
        assertThat(card.hiddenArgs()).isFalse();
        assertThat(saves.requestSave(owner, origin.id(), snapshotId).actionId()).isEqualTo(results.getFirst().actionId());
        code(() -> approvals.approve(owner, action.publicId(), GrantPeriod.TODAY), ErrorCode.VALIDATION_FAILED);
        assertThat(actionRows.findAll().getFirst().status()).isEqualTo(ActionStatus.PENDING);
    }

    @Test
    @DisplayName("catalog가 시작을 선언하지 않으면 승인 줄을 실행 없이 거절하고 snapshot은 STALE이다")
    void refusesWithoutStartWhenCapabilityIsNotDeclared() {
        catalog(false);
        UUID snapshotId = preview();
        var request = saves.requestSave(owner, origin.id(), snapshotId);
        var view = approvals.approve(owner, request.actionId(), null);
        assertThat(view.status()).isEqualTo(ActionStatus.REJECTED);
        assertThat(view.errorCode()).isEqualTo("not_executable");
        assertThat(starter.started()).isEmpty();
        var job = jobRows.findAll().getFirst();
        assertThat(job.status()).isEqualTo(ContentDraftJobStatus.STALE);
        assertThat(job.jobId()).isNull();
        assertThat(saves.requestSave(owner, origin.id(), snapshotId).status()).isEqualTo(ContentDraftJobStatus.STALE);
        assertThat(actionRows.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 승인은 jobId 하나를 예약하고 시작을 한 번만 보내며 재요청은 같은 job을 돌려준다")
    void reservesOneJobAndStartsOnceUnderConcurrentApproval() throws Exception {
        UUID snapshotId = preview();
        var request = saves.requestSave(owner, origin.id(), snapshotId);
        var outcomes = new ArrayList<String>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var pending = List.of(1, 2).stream().map(i -> pool.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                try {
                    return approvals.approve(owner, request.actionId(), null).status().name();
                } catch (ApiException ex) {
                    return ex.code().name();
                }
            })).toList();
            start.countDown();
            for (var future : pending) {
                outcomes.add(future.get(20, TimeUnit.SECONDS));
            }
        }
        assertThat(outcomes).containsExactlyInAnyOrder("EXECUTING", "CONNECTOR_ACTION_NOT_PENDING");
        assertThat(starter.started()).hasSize(1);
        var job = jobRows.findAll().getFirst();
        assertThat(job.status()).isEqualTo(ContentDraftJobStatus.SAVING);
        assertThat(job.jobId()).isEqualTo(starter.started().getFirst().jobId().toString());
        assertThat(job.approvedBy()).isEqualTo(owner.id());
        var snapshot = starter.started().getFirst().snapshot();
        assertThat(snapshot.path("snapshotHash").stringValue()).isEqualTo(snapshotRows.findAll().getFirst().snapshotHash());
        assertThat(starter.started().getFirst().profile()).isEqualTo(binding.agent().hermesProfile());
        var again = saves.requestSave(owner, origin.id(), snapshotId);
        assertThat(again.jobId().toString()).isEqualTo(job.jobId());
        assertThat(jobQueries.refresh(owner, conversation.id(), draftId, snapshotId).status()).isEqualTo(ContentDraftJobStatus.SAVING);
        code(() -> approvals.approve(owner, request.actionId(), null), ErrorCode.CONNECTOR_ACTION_NOT_PENDING);
        assertThat(starter.started()).hasSize(1);
        // 실행 중인 원고 저장 줄이 있는 동안 기존 연결 해제는 거절하고 바인딩 판을 그대로 둔다.
        var before = bindings.findById(binding.id()).orElseThrow().updatedAt();
        code(() -> bindingService.unbind(owner, binding.agent().code(), CONNECTOR), ErrorCode.CONNECTOR_ACTION_EXECUTING);
        assertThat(bindings.findById(binding.id()).orElseThrow().updatedAt()).isEqualTo(before);
    }

    @Test
    @DisplayName("시작 응답을 잃으면 UNKNOWN으로 두고 다시 요청·승인해도 시작을 더 보내지 않는다")
    void keepsUnknownWithoutRestartWhenStartAnswerIsLost() {
        starter.answer(call -> {
            throw new IllegalStateException("synthetic timeout");
        });
        UUID snapshotId = preview();
        var request = saves.requestSave(owner, origin.id(), snapshotId);
        assertThat(approvals.approve(owner, request.actionId(), null).status()).isEqualTo(ActionStatus.UNKNOWN);
        assertThat(jobRows.findAll().getFirst().status()).isEqualTo(ContentDraftJobStatus.UNKNOWN);
        assertThat(saves.requestSave(owner, origin.id(), snapshotId).status()).isEqualTo(ContentDraftJobStatus.UNKNOWN);
        code(() -> approvals.approve(owner, request.actionId(), null), ErrorCode.CONNECTOR_ACTION_NOT_PENDING);
        assertThat(starter.started()).hasSize(1);
        assertThat(actionRows.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("worker가 시작 전에 거절하면 FAILED이고 같은 snapshot으로 다시 시작하지 않는다")
    void failsWithoutRestartWhenWorkerRejectsBeforeStart() {
        starter.answer(call -> Optional.of("NAVER_BLOG_BUSY"));
        UUID snapshotId = preview();
        var request = saves.requestSave(owner, origin.id(), snapshotId);
        assertThat(approvals.approve(owner, request.actionId(), null).status()).isEqualTo(ActionStatus.FAILED);
        var job = jobRows.findAll().getFirst();
        assertThat(job.status()).isEqualTo(ContentDraftJobStatus.FAILED);
        assertThat(job.errorCode()).isEqualTo("NAVER_BLOG_BUSY");
        assertThat(saves.requestSave(owner, origin.id(), snapshotId).status()).isEqualTo(ContentDraftJobStatus.FAILED);
        assertThat(starter.started()).hasSize(1);
    }

    @Test
    @DisplayName("요청 뒤 원고 revision이 바뀌거나 사진이 삭제되면 승인은 실행 없이 STALE로 끝난다")
    void refusesApprovalAfterRevisionChangeOrPhotoDeletion() {
        UUID snapshotId = preview();
        var request = saves.requestSave(owner, origin.id(), snapshotId);
        drafts.revise(owner, conversation.id(), draftId, 1, inputDraft("수정", true));
        assertThat(approvals.approve(owner, request.actionId(), null).status()).isEqualTo(ActionStatus.REJECTED);
        assertThat(jobRows.findAll().getFirst().status()).isEqualTo(ContentDraftJobStatus.STALE);
        assertThat(jobRows.findAll().getFirst().errorCode()).isEqualTo("CONTENT_DRAFT_STALE");
        jobRows.deleteAll();
        snapshotRows.deleteAll();
        actionRows.deleteAll();
        UUID second = snapshots.preview(owner, conversation.id(), draftId, 2, binding.id(), origin.id()).snapshotId();
        var next = saves.requestSave(owner, origin.id(), second);
        attachmentCleaner.delete(photo, NOW);
        assertThat(approvals.approve(owner, next.actionId(), null).status()).isEqualTo(ActionStatus.REJECTED);
        assertThat(starter.started()).isEmpty();
    }

    @Test
    @DisplayName("worker 허가는 시작과 저장을 각각 한 번만 주고 manifest 밖 사진 위치를 거절한다")
    void authorizesStartPhotoAndSaveExactlyOnce() {
        String ticket = approved();
        forbidden(() -> authorizer.authorize(ticket, "save", null));
        forbidden(() -> authorizer.authorize(ticket, "photo", 0));
        authorizer.authorize(ticket, "start", null);
        forbidden(() -> authorizer.authorize(ticket, "start", null));
        authorizer.authorize(ticket, "photo", 0);
        authorizer.authorize(ticket, "photo", 1);
        forbidden(() -> authorizer.authorize(ticket, "photo", 2));
        authorizer.authorize(ticket, "save", null);
        forbidden(() -> authorizer.authorize(ticket, "save", null));
        forbidden(() -> authorizer.authorize(ticket, "photo", 0));
        var job = jobRows.findAll().getFirst();
        assertThat(job.startClaimedAt()).isNotNull();
        assertThat(job.saveClaimedAt()).isNotNull();
        code(() -> authorizer.authorize(ticket, "photo", null), ErrorCode.VALIDATION_FAILED);
        code(() -> authorizer.authorize(ticket, "save", 0), ErrorCode.VALIDATION_FAILED);
        code(() -> authorizer.authorize(ticket + "x", "save", null), ErrorCode.UNAUTHENTICATED);
    }

    @Test
    @DisplayName("사용자 끄기·에이전트 비활성·origin 취소가 먼저 커밋되면 저장 허가는 거절되고 claim은 비어 있다")
    void rejectsSaveAfterRevocationCommittedFirst() {
        String ticket = approved();
        authorizer.authorize(ticket, "start", null);
        personAccess.setEnabled(person.id(), false);
        forbidden(() -> authorizer.authorize(ticket, "save", null));
        personAccess.setEnabled(person.id(), true);
        jdbc.update("update agent set enabled=false where id=?", binding.agent().id());
        forbidden(() -> authorizer.authorize(ticket, "save", null));
        jdbc.update("update agent set enabled=true where id=?", binding.agent().id());
        recorder.cancel(executions.findById(origin.id()).orElseThrow());
        forbidden(() -> authorizer.authorize(ticket, "save", null));
        assertThat(jobRows.findAll().getFirst().saveClaimedAt()).isNull();
    }

    @Test
    @DisplayName("실행 취소가 행 잠금을 쥔 동안 저장 허가는 기다렸다가 커밋을 보고 거절하고 rollback이면 허가한다")
    void waitsForHeldCancelThenFollowsItsOutcome() throws Exception {
        String ticket = approved();
        authorizer.authorize(ticket, "start", null);
        assertThat(heldCancel(ticket, false)).isEqualTo("FORBIDDEN");
        jdbc.update("update agent_execution set status='RUNNING', finished_at=null where id=?", origin.id());
        assertThat(heldCancel(ticket, true)).isEqualTo("ALLOWED");
        assertThat(jobRows.findAll().getFirst().saveClaimedAt()).isNotNull();
    }

    @Test
    @DisplayName("사용자 끄기가 허용 목록 행을 쥔 동안 저장 허가는 기다렸다가 끄기 커밋을 보고 거절한다")
    void waitsForHeldDisableThenRejects() throws Exception {
        String ticket = approved();
        authorizer.authorize(ticket, "start", null);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                personAccess.setEnabled(person.id(), false);
                people.flush();
                held.countDown();
                await(release);
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var claim = pool.submit(() -> outcome(() -> authorizer.authorize(ticket, "save", null)));
            Thread.sleep(300);
            assertThat(claim.isDone()).isFalse();
            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            assertThat(claim.get(10, TimeUnit.SECONDS)).isEqualTo("FORBIDDEN");
        }
        assertThat(jobRows.findAll().getFirst().saveClaimedAt()).isNull();
    }

    @Test
    @DisplayName("실제 HTTP는 JWT 없이 정확한 경로에 닿고 위조 ticket은 401, 유효 ticket은 허가만 돌려준다")
    void authorizesOverHttpWithoutUserJwt() throws Exception {
        String ticket = approved();
        var forged = post("{\"ticket\":\"" + ticket.substring(0, ticket.length() - 2) + "AA\",\"stage\":\"start\"}");
        assertThat(forged.statusCode()).isEqualTo(401);
        assertThat(forged.body()).doesNotContain(ticket, "비밀");
        assertThat(post("{\"ticket\":\"" + ticket + "\",\"stage\":\"start\",\"x\":1}").statusCode()).isEqualTo(400);
        var allowed = post("{\"ticket\":\"" + ticket + "\",\"stage\":\"start\"}");
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(json.readTree(allowed.body()).propertyNames()).containsExactly("allowed");
        var again = post("{\"ticket\":\"" + ticket + "\",\"stage\":\"start\"}");
        assertThat(again.statusCode()).isEqualTo(403);
        assertThat(again.body()).doesNotContain(ticket, "비밀");
    }

    @Test
    @DisplayName("원래 대화의 job 목록은 cursor로 한 페이지씩 보관 상태만 돌려준다")
    void pagesStoredJobsWithSnapshotCursor() {
        UUID snapshotId = preview();
        saves.requestSave(owner, origin.id(), snapshotId);
        var page = jobQueries.list(owner, conversation.id(), null, 20);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.snapshotId()).isEqualTo(snapshotId);
            assertThat(item.draftId()).isEqualTo(draftId);
            assertThat(item.revision()).isEqualTo(1);
        });
        assertThat(page.nextBeforeSnapshotId()).isNull();
        assertThat(jobQueries.list(owner, conversation.id(), snapshotId, 20).items()).isEmpty();
        code(() -> jobQueries.list(owner, conversation.id(), UUID.randomUUID(), 20), ErrorCode.CONVERSATION_NOT_FOUND);
        code(() -> jobQueries.list(owner, conversation.id(), null, 21), ErrorCode.VALIDATION_FAILED);
        code(() -> jobQueries.refresh(owner, conversation.id(), UUID.randomUUID(), snapshotId), ErrorCode.CONVERSATION_NOT_FOUND);
    }

    private String approved() {
        UUID snapshotId = preview();
        var request = saves.requestSave(owner, origin.id(), snapshotId);
        assertThat(approvals.approve(owner, request.actionId(), null).status()).isEqualTo(ActionStatus.EXECUTING);
        return starter.started().getLast().ticket();
    }

    private String heldCancel(String ticket, boolean rollback) throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                recorder.cancel(executions.findById(origin.id()).orElseThrow());
                executions.flush();
                held.countDown();
                await(release);
                if (rollback) {
                    status.setRollbackOnly();
                }
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var claim = pool.submit(() -> outcome(() -> authorizer.authorize(ticket, "save", null)));
            Thread.sleep(300);
            assertThat(claim.isDone()).isFalse();
            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            return claim.get(10, TimeUnit.SECONDS);
        }
    }

    private UUID preview() {
        return snapshots.preview(owner, conversation.id(), draftId, 1, binding.id(), origin.id()).snapshotId();
    }

    private void catalog(boolean start) {
        var declaration = new ConnectorSnapshotDeclaration("content-snapshot-v1", "compile_snapshot", "start_snapshot", "status_snapshot",
            Map.of("compile", true, "start", start, "status", start));
        var tools = List.of(new ConnectorTool("verify", "READ", "none", null, null),
            new ConnectorTool("save_draft", "WRITE", "required", "임시저장", null));
        when(catalog.readCatalog()).thenReturn(List.of(new ConnectorManifest(CONNECTOR, "검사", "", List.of(), "verify", CONNECTOR,
            List.of(), false, 2, tools, List.of(), null, false, false, null, null, null, declaration)));
        try {
            var field = cache.getClass().getDeclaredField("readAt");
            field.setAccessible(true);
            field.set(cache, null);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private HttpResponse<String> post(String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/content-draft-jobs/authorize"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String outcome(Runnable action) {
        try {
            action.run();
            return "ALLOWED";
        } catch (ApiException ex) {
            return ex.code().name();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static void forbidden(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.FORBIDDEN));
    }
}
