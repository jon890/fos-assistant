package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bifos.assistant.chat.application.ContentDraftBindingAccess;
import com.bifos.assistant.chat.application.ContentDraftInputReader;
import com.bifos.assistant.chat.domain.ContentDraftBindingChoiceException;
import com.bifos.assistant.chat.application.ContentDraftSaveRequests;
import com.bifos.assistant.chat.application.ContentDraftService;
import com.bifos.assistant.chat.application.ContentDraftSnapshots;
import com.bifos.assistant.chat.application.model.CanonicalDraft;
import com.bifos.assistant.chat.application.model.ContentDraftSaveRequestView;
import com.bifos.assistant.chat.application.model.ContentDraftSnapshotView;
import com.bifos.assistant.chat.domain.ContentDraftException;
import com.bifos.assistant.chat.domain.type.ContentDraftJobStatus;
import com.bifos.assistant.mcp.application.dto.McpCallContext;
import com.bifos.assistant.mcp.application.dto.McpCaller;
import com.bifos.assistant.mcp.application.McpCallerResolver;
import com.bifos.assistant.mcp.application.McpContentDraftSnapshots;
import com.bifos.assistant.mcp.application.McpContentDraftTools;
import com.bifos.assistant.mcp.application.McpCurrentCallerGuard;
import com.bifos.assistant.mcp.application.McpFinancialLimitTools;
import com.bifos.assistant.mcp.application.McpMediaObservationTools;
import com.bifos.assistant.mcp.application.dto.McpPrincipal;
import com.bifos.assistant.mcp.application.McpToolService;
import com.bifos.assistant.mcp.presentation.McpController;
import com.bifos.assistant.proactive.application.ProactiveCheckGuard;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 원고 preview·저장 요청 MCP 도구의 인자 검사, 사전·사후 권한 검사, 결과 모양을 실제 controller 분기로 검사한다. */
class McpContentDraftSnapshotsTest {
    private static final CurrentUser OWNER = new CurrentUser(7L, "owner@example.com", "주인", 1L, UserRole.MEMBER);
    private static final UUID DRAFT = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100");
    private static final UUID SNAPSHOT = UUID.fromString("1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d");
    private static final String MARKER = "SYNTHETIC_SNAPSHOT_BODY_5521";
    private final JsonMapper json = JsonMapper.builder().build();
    private final ContentDraftSnapshots snapshots = mock(ContentDraftSnapshots.class);
    private final ContentDraftSaveRequests saves = mock(ContentDraftSaveRequests.class);
    private final ContentDraftBindingAccess bindings = mock(ContentDraftBindingAccess.class);
    private final McpCurrentCallerGuard guard = mock(McpCurrentCallerGuard.class);
    private final McpCallerResolver callers = mock(McpCallerResolver.class);
    private final McpToolService tools = mock(McpToolService.class);
    private final ProactiveCheckGuard checks = mock(ProactiveCheckGuard.class);
    private McpController controller;
    private McpCaller caller;


    @BeforeEach
    void setUp() {
        var drafts = new McpContentDraftTools(mock(ContentDraftService.class), mock(ContentDraftInputReader.class), guard, json);
        var tool = new McpContentDraftSnapshots(snapshots, saves, bindings, drafts);
        controller = new McpController(tools, callers, mock(McpMediaObservationTools.class), drafts, tool,
            mock(McpFinancialLimitTools.class), checks, mock(BuildProperties.class));
        var origin = mock(AgentExecution.class);
        when(origin.id()).thenReturn(31L);
        when(origin.conversationId()).thenReturn(41L);
        caller = new McpCaller(OWNER, origin, new McpCallContext("root", "session", "call_1"));
        when(callers.resolve(any(), any(), any())).thenReturn(caller);
        when(checks.checkOf(any())).thenReturn(Optional.empty());
        when(tools.invalidContext()).thenReturn(Map.of("isError", true));
    }


    @Test
    @DisplayName("preview 는 서명한 origin 의 대화와 실행으로만 만들고 안전한 결과를 외부 데이터로 감싼다")
    void previewUsesSignedOriginAndWrapsSafeView() {
        when(snapshots.preview(OWNER, 41L, DRAFT, 3L, 9L, 31L)).thenReturn(view());

        var result = result(call(McpContentDraftSnapshots.PREVIEW, previewArgs()));

        assertThat(result.path("isError").booleanValue()).isFalse();
        String text = text(result);
        assertThat(text).contains("<external-data>", "\"assetIds\":[\"11\"]", "\"omittedAssetIds\":[\"12\"]")
            .doesNotContain("fileName", "photoDir", "payload", "ticket");
        verify(guard, times(2)).requireCurrent(caller);
    }


    @Test
    @DisplayName("인자는 draftId·revision·bindingId 와 snapshotId 뿐이고 그 밖의 모양은 -32602 고정 문구다")
    void rejectsUnknownNullAndMistypedArguments() {
        List<ObjectNode> previews = List.of(
            previewArgs().put("ownerUserId", 7),
            previewArgs().put("photoDir", "/tmp"),
            previewArgs().putNull("bindingId"),
            previewArgs().put("revision", "3"),
            previewArgs().put("revision", 3.5),
            previewArgs().put("revision", 0),
            previewArgs().put("bindingId", 0),
            previewArgs().put("bindingId", "9"),
            previewArgs().put("draftId", DRAFT.toString().toUpperCase()),
            (ObjectNode)previewArgs().without("revision"));
        for (ObjectNode args : previews) {
            assertThat(error(call(McpContentDraftSnapshots.PREVIEW, args))).as(args.toString()).isEqualTo("인자 형식이 올바르지 않습니다.");
        }
        List<ObjectNode> savesArgs = List.of(json.createObjectNode(), saveArgs().put("draftId", DRAFT.toString()),
            json.createObjectNode().putNull("snapshotId"), json.createObjectNode().put("snapshotId", "not-a-uuid"),
            json.createObjectNode().put("snapshotId", 5));
        for (ObjectNode args : savesArgs) {
            assertThat(error(call(McpContentDraftSnapshots.SAVE, args))).as(args.toString()).isEqualTo("인자 형식이 올바르지 않습니다.");
        }
        verifyNoInteractions(snapshots, saves);
    }


    @Test
    @DisplayName("bindingId 를 생략하면 요청자 에이전트의 유일한 snapshot 바인딩을 쓰고 정하지 못하면 까닭을 싣는다")
    void choosesSoleBindingOrExplainsWhyNot() {
        var origin = caller.originExecution();
        when(origin.agentId()).thenReturn(5L);
        when(bindings.soleSnapshotBinding(OWNER, 5L)).thenReturn(9L);
        when(snapshots.preview(OWNER, 41L, DRAFT, 3L, 9L, 31L)).thenReturn(view());

        var chosen = result(call(McpContentDraftSnapshots.PREVIEW, (ObjectNode)previewArgs().without("bindingId")));

        assertThat(chosen.path("isError").booleanValue()).isFalse();
        when(bindings.soleSnapshotBinding(OWNER, 5L)).thenThrow(new ContentDraftBindingChoiceException(ContentDraftBindingChoiceException.MANY));
        var many = result(call(McpContentDraftSnapshots.PREVIEW, (ObjectNode)previewArgs().without("bindingId")));
        assertThat(many.path("isError").booleanValue()).isTrue();
        assertThat(json.readTree(text(many)).path("code").stringValue()).isEqualTo("VALIDATION_FAILED");
        assertThat(json.readTree(text(many)).path("message").stringValue()).isEqualTo(ContentDraftBindingChoiceException.MANY);
        verify(snapshots, times(1)).preview(any(), any(), any(), anyLong(), any(), any());
    }


    @Test
    @DisplayName("사전 검사가 실패하면 서비스와 외부 호출이 0회다")
    void blocksBeforeServiceWhenCallerIsNotCurrent() {
        doThrow(new ApiException(ErrorCode.MCP_CALL_CONTEXT_INVALID, "x")).when(guard).requireCurrent(caller);

        var preview = result(call(McpContentDraftSnapshots.PREVIEW, previewArgs()));
        var save = result(call(McpContentDraftSnapshots.SAVE, saveArgs()));

        assertThat(preview.path("isError").booleanValue()).isTrue();
        assertThat(save.path("isError").booleanValue()).isTrue();
        assertThat(text(preview)).doesNotContain(MARKER, SNAPSHOT.toString());
        verify(snapshots, never()).preview(any(), any(), any(), anyLong(), any(), any());
        verifyNoInteractions(saves);
    }


    @Test
    @DisplayName("사후 검사가 실패하면 만든 행은 두되 결과의 본문과 식별자를 차단한다")
    void blocksResultWhenCallerLosesPermissionAfterwards() {
        var calls = new AtomicInteger();
        doNothing().doThrow(new ApiException(ErrorCode.MCP_CALL_CONTEXT_INVALID, "x")).when(guard).requireCurrent(caller);
        when(saves.requestSave(OWNER, 31L, SNAPSHOT)).thenAnswer(invocation -> {
            calls.incrementAndGet();
            return new ContentDraftSaveRequestView(SNAPSHOT, UUID.randomUUID(), null, ContentDraftJobStatus.PREVIEW_READY);
        });

        var result = result(call(McpContentDraftSnapshots.SAVE, saveArgs()));

        assertThat(calls).hasValue(1);
        assertThat(result.path("isError").booleanValue()).isTrue();
        assertThat(text(result)).doesNotContain(SNAPSHOT.toString(), "PREVIEW_READY");
    }


    @Test
    @DisplayName("같은 snapshot 의 재호출은 같은 승인 줄과 job 을 돌려주고 typed 오류는 code 만 싣는다")
    void returnsSameRequestAndOnlyErrorCode() {
        var action = UUID.randomUUID();
        when(saves.requestSave(OWNER, 31L, SNAPSHOT))
            .thenReturn(new ContentDraftSaveRequestView(SNAPSHOT, action, null, ContentDraftJobStatus.PREVIEW_READY));

        String first = text(result(call(McpContentDraftSnapshots.SAVE, saveArgs())));
        String second = text(result(call(McpContentDraftSnapshots.SAVE, saveArgs())));

        assertThat(first).isEqualTo(second).contains(action.toString(), "PREVIEW_READY");
        when(snapshots.preview(eq(OWNER), eq(41L), eq(DRAFT), eq(3L), eq(9L), eq(31L)))
            .thenThrow(new ContentDraftException(ErrorCode.CONTENT_DRAFT_STALE, null));
        var stale = result(call(McpContentDraftSnapshots.PREVIEW, previewArgs()));
        assertThat(stale.path("isError").booleanValue()).isTrue();
        assertThat(text(stale)).isEqualTo("{\"code\":\"CONTENT_DRAFT_STALE\"}");
    }


    @Test
    @DisplayName("두 도구 schema 는 소유자·경로 없이 필요한 인자만 받는다")
    void definesStrictSchemas() {
        var tool = new McpContentDraftSnapshots(snapshots, saves, bindings, mock(McpContentDraftTools.class));
        JsonNode preview = json.valueToTree(tool.previewDefinition()).path("inputSchema");
        JsonNode save = json.valueToTree(tool.saveDefinition()).path("inputSchema");

        assertThat(preview.path("additionalProperties").booleanValue()).isFalse();
        assertThat(preview.path("properties").propertyNames()).containsExactlyInAnyOrder("draftId", "revision", "bindingId");
        assertThat(preview.path("required")).hasSize(2);
        assertThat(save.path("additionalProperties").booleanValue()).isFalse();
        assertThat(save.path("properties").propertyNames()).containsExactly("snapshotId");
    }


    private ContentDraftSnapshotView view() {
        var blocks = json.createArrayNode();
        blocks.addObject().put("type", "paragraph").put("text", MARKER);
        blocks.addObject().put("type", "image").put("assetId", "11");
        var draft = new CanonicalDraft(1, DRAFT, 3, UUID.randomUUID(), "제목", "분류", json.createArrayNode(), blocks);
        return new ContentDraftSnapshotView(SNAPSHOT, DRAFT, 3, "a".repeat(64), draft, List.of("11"), List.of("12"),
            Instant.parse("2026-10-12T00:00:00Z"));
    }


    private ObjectNode previewArgs() {
        return json.createObjectNode().put("draftId", DRAFT.toString()).put("revision", 3).put("bindingId", 9);
    }


    private ObjectNode saveArgs() {
        return json.createObjectNode().put("snapshotId", SNAPSHOT.toString());
    }


    private JsonNode call(String name, ObjectNode arguments) {
        var copy = arguments.deepCopy();
        copy.putObject(McpCallContext.FIELD).put("sig", "signed");
        var request = json.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
        request.putObject("params").put("name", name).set("arguments", copy);
        return json.valueToTree(controller.handle(null, new McpPrincipal(1L, "profile", "hash"), request).getBody());
    }


    private static JsonNode result(JsonNode response) {
        assertThat(response.has("error")).as(response.toString()).isFalse();
        return response.path("result");
    }


    private static String error(JsonNode response) {
        assertThat(response.path("error").path("code").intValue()).isEqualTo(-32602);
        return response.path("error").path("data").stringValue();
    }


    private static String text(JsonNode result) {
        return result.path("content").path(0).path("text").stringValue();
    }
}
