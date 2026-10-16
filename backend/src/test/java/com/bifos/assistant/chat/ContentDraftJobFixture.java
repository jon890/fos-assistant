package com.bifos.assistant.chat;

import com.bifos.assistant.shared.auth.CurrentUser;
import java.nio.file.Files;
import java.util.Base64;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import com.bifos.assistant.hermes.dto.ContentDraftWorkerStamp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.admin.application.AgentAdminService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ContentDraftJobQueries;
import com.bifos.assistant.chat.application.ContentDraftSaveRequests;
import com.bifos.assistant.chat.application.ContentDraftSnapshotHash;
import com.bifos.assistant.chat.application.ContentDraftSnapshots;
import com.bifos.assistant.chat.infra.ContentDraftJobRepository;
import com.bifos.assistant.chat.infra.ContentDraftSnapshotRepository;
import com.bifos.assistant.connector.application.ConnectorActionService;
import com.bifos.assistant.connector.application.ConnectorBindingService;
import com.bifos.assistant.connector.application.ConnectorCatalogCache;
import com.bifos.assistant.connector.application.ContentDraftJobAuthorizer;
import com.bifos.assistant.connector.application.ContentDraftSaveApproval;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.ConnectorConnection;
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
import com.bifos.assistant.testsupport.RecordingContentDraftJobStarter;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/** 예약 job과 실제 CP HTTP 검사가 함께 쓰는 합성 데이터다. */
public abstract class ContentDraftJobFixture extends ContentDraftFixture {
    protected static final String CONNECTOR = "snapshot-test";

    @Autowired
    protected ContentDraftSnapshots snapshots;
    @Autowired
    protected ContentDraftSaveRequests saves;
    @Autowired
    protected ContentDraftJobQueries jobQueries;
    @Autowired
    protected ContentDraftJobAuthorizer authorizer;
    @Autowired
    protected ConnectorActionService approvals;
    @Autowired
    protected ContentDraftSaveApproval contentApproval;
    @Autowired
    protected ConnectorBindingService bindingService;
    @Autowired
    protected ConnectorActionRepository actionRows;
    @Autowired
    protected ContentDraftSnapshotRepository snapshotRows;
    @Autowired
    protected ContentDraftJobRepository jobRows;
    @Autowired
    protected AgentRepository agents;
    @Autowired
    protected AgentAdminService agentAdmin;
    @Autowired
    protected AgentExecutionRepository executions;
    @Autowired
    protected ExecutionRecorder recorder;
    @Autowired
    protected AllowedPersonRepository people;
    @Autowired
    protected PersonAccessService personAccess;
    @Autowired
    protected ConnectorConnectionRepository connections;
    @Autowired
    protected ConnectorBindingRepository bindings;
    @Autowired
    protected HermesConnectorClient catalog;
    @Autowired
    protected HttpContentDraftSnapshotClient compiler;
    @Autowired
    protected ConnectorCatalogCache cache;
    @Autowired
    protected RecordingContentDraftJobStarter starter;
    @LocalServerPort
    protected int port;

    protected ConnectorBinding binding;
    protected AgentExecution origin;
    protected AllowedPerson person;
    protected UUID draftId;


    protected CurrentUser loopbackOwner() {
        return owner;
    }


    protected Long loopbackConversationId() {
        return conversation.id();
    }


    protected Instant loopbackInstant() {
        return NOW;
    }


    protected List<ObjectNode> loopbackPhotos(JsonNode snapshot) throws Exception {
        var result = new ArrayList<ObjectNode>();
        for (var asset : snapshot.path("assetManifest")) {
            var attachment = attachments.findById(Long.parseLong(asset.path("assetId").stringValue())).orElseThrow();
            var bytes = Files.readAllBytes(file(attachment));
            result.add(JsonMapper.builder().build().createObjectNode()
                .put("fileName", asset.path("fileName").stringValue()).put("bytes", Base64.getEncoder().encodeToString(bytes)));
        }
        return result;
    }


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
        prepareSavePhotos();
        draftId = drafts.create(owner, conversation.id(), inputDraft("비밀 원고", true)).draft().draftId();
        starter.answer(call -> Optional.empty());
    }


    protected void prepareSavePhotos() {
    }


    protected void useDistinctSecondPhoto() {
        secondPhoto = photo(owner, conversation, MediaObservationPixelFixture.gif(2));
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


    protected UUID preview() {
        return snapshots.preview(owner, conversation.id(), draftId, 1, binding.id(), origin.id()).snapshotId();
    }


    /** 같은 stamp 를 되돌려 주는 worker 상태 응답이다. */
    protected ObjectNode statusAnswer(ContentDraftWorkerStamp stamp, String status, boolean clicked) {
        return json.createObjectNode().put("job_id", stamp.jobId().toString()).set("stamp", json.valueToTree(stamp))
            .put("status", status).put("stage", "state").put("save_clicked", clicked)
            .putNull("finished_at").putNull("saved_before").putNull("saved_after").putNull("error_code");
    }


    protected void catalog(boolean start) {
        var declaration = new ConnectorSnapshotDeclaration("content-snapshot-v1", "compile_snapshot", "start_snapshot", "status_snapshot",
            Map.of("compile", true, "start", start, "status", start));
        var tools = List.of(new ConnectorTool("verify", "READ", "none", null, null),
            new ConnectorTool("save_draft", "WRITE", "required", "임시저장", null));
        when(catalog.readCatalog()).thenReturn(List.of(new ConnectorManifest(CONNECTOR, "검사", "", List.of(), "verify", CONNECTOR,
            2, tools, List.of(), null, false, false, null, null, null, declaration)));
        try {
            var field = cache.getClass().getDeclaredField("readAt");
            field.setAccessible(true);
            field.set(cache, null);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

}
