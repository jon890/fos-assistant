package com.bifos.assistant.mcp.application;

import com.bifos.assistant.chat.application.ContentDraftBindingAccess;
import com.bifos.assistant.chat.application.ContentDraftInputReader;
import com.bifos.assistant.chat.application.ContentDraftSaveRequests;
import com.bifos.assistant.chat.application.ContentDraftSnapshots;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 기존 원고의 불변 preview 를 만들고 그 snapshot 의 저장 승인을 요청한다. 실제 저장은 사용자가 승인 카드에서 한다. */
@Service
@RequiredArgsConstructor
public class McpContentDraftSnapshots {
    public static final String PREVIEW = "content_draft_preview";
    public static final String SAVE = "content_draft_save";
    private final ContentDraftSnapshots snapshots;
    private final ContentDraftSaveRequests saves;
    private final ContentDraftBindingAccess bindings;
    private final McpContentDraftTools drafts;


    /**
     * @param bindingId 생략하면 null. 요청자 에이전트의 snapshot 지원 바인딩이 정확히 하나일 때만 그것을 쓴다. 지정한 번호도
     * 같은 에이전트·요청자 범위인지 preview 가 검사한다
     */
    public Map<String, Object> preview(McpCaller caller, UUID draftId, long revision, Long bindingId) {
        return drafts.guarded(caller, () -> {
            var origin = caller.originExecution();
            Long binding = bindingId == null ? bindings.soleSnapshotBinding(caller.user(), origin.agentId()) : bindingId;
            return snapshots.preview(caller.user(), origin.conversationId(), draftId, revision, binding, origin.id());
        });
    }


    /** 같은 snapshot 의 재호출은 앞의 승인 줄과 job 을 그대로 돌려주며 외부를 부르지 않는다. */
    public Map<String, Object> save(McpCaller caller, UUID snapshotId) {
        return drafts.guarded(caller, () -> saves.requestSave(caller.user(), caller.originExecution().id(), snapshotId));
    }


    public Map<String, Object> previewDefinition() {
        return Map.of("name", PREVIEW, "description",
            "현재 대화의 원고 revision 을 네이버 임시저장용으로 컴파일해 바뀌지 않는 preview 를 만든다. "
                + "결과의 preview 와 포함한 사진 assetIds, 빠진 사진 omittedAssetIds 를 사용자에게 그대로 알린다. 모든 사진을 썼다고 말하지 않는다. "
                + "bindingId 는 생략한다. 원고 저장을 지원하는 연결이 여럿이라는 오류를 받았을 때만 지정한다.",
            "inputSchema", object(Map.of("draftId", uuid(), "revision", positive(ContentDraftInputReader.MAX_REVISION),
                "bindingId", positive(Long.MAX_VALUE)), List.of("draftId", "revision")));
    }


    public Map<String, Object> saveDefinition() {
        return Map.of("name", SAVE, "description",
            "content_draft_preview 로 만든 snapshot 의 임시저장 승인을 요청한다. 저장은 사용자가 승인 카드에서 승인해야 시작된다. "
                + "같은 snapshotId 를 다시 보내면 앞의 요청을 그대로 돌려주며 저장을 다시 시작하지 않는다. 원고를 고쳤으면 새 preview 를 만든다.",
            "inputSchema", object(Map.of("snapshotId", uuid()), List.of("snapshotId")));
    }


    private static Map<String, Object> uuid() {
        return Map.of("type", "string", "format", "uuid", "pattern", "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }


    private static Map<String, Object> positive(long max) {
        return Map.of("type", "integer", "minimum", 1, "maximum", max);
    }


    private static LinkedHashMap<String, Object> object(Map<String, Object> properties, List<String> required) {
        return new LinkedHashMap<>(Map.of("type", "object", "additionalProperties", false, "properties", properties, "required", required));
    }
}
