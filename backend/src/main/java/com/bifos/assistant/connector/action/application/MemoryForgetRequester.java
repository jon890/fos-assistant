package com.bifos.assistant.connector.action.application;

import com.bifos.assistant.connector.action.domain.ControlPlaneActions;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.mcp.application.port.MemoryForgetRequests;
import com.bifos.assistant.memory.application.MemoryForgetting;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 기억 지우기 승인 카드를 띄운다(스펙 service-concepts REQ13). 지울 수 없는 기억이면 카드 없이 까닭을 돌려준다.
 *
 * <p>결과에 기억의 제목과 내용을 넣지 않는다. 그 기억을 읽을 권한이 없는 에이전트가 번호만으로 불러도 글은 카드로 사용자에게만 보인다.
 */
@Component
@RequiredArgsConstructor
class MemoryForgetRequester implements MemoryForgetRequests {
    /** 카드에 보일 내용의 글자 수 상한이다. 넘으면 앞부분만 보인다. */
    static final int CONTENT_PREVIEW = 500;
    private final MemoryForgetting forgetting;
    private final ControlPlaneApprovals approvals;


    @Override
    public Map<String, Object> request(CurrentUser user, Long originExecutionId, Long memoryId) {
        Memory memory;
        try {
            memory = forgetting.target(user, memoryId);
        } catch (ApiException ex) {
            if (ex.code() == ErrorCode.FORBIDDEN) {
                return result("forbidden", "그룹 기억은 관리자만 지울 수 있다. 카드를 띄우지 않았다. 지우지 못한 까닭을 알린다.");
            }
            if (ex.code() != ErrorCode.MEMORY_NOT_FOUND) {
                throw ex;
            }
            return result("not_found",
                "그 번호의 기억이 없다. 문서는 이 도구로 지우지 못한다. 번호를 다시 확인하거나 /memory 화면에서 지우라고 안내한다.");
        }
        Map<String, String> args = new LinkedHashMap<>();
        boolean readable = memory.readable();
        args.put("memory", readable ? memory.title() : "열 수 없는 기억");
        args.put("content", readable ? preview(memory.content()) : "내용을 열 수 없어요");
        args.put("scope", memory.scope() == MemoryScope.GROUP ? "그룹 기억" : "내 기억");
        args.put("memory_id", memoryId.toString());
        args.put("revision", Integer.toString(memory.revision()));
        var request = approvals.request(user, originExecutionId, ControlPlaneActions.MEMORY_FORGET, memoryId + ":" + memory.revision(), args);
        if (request.status() != ActionStatus.PENDING) {
            return result("closed", "같은 요청의 승인 카드가 이미 끝났다(" + request.status().name()
                + "). 다시 지우려면 사용자에게 확인한 뒤 사용자의 다음 요청에서 부른다.");
        }
        return result(REQUESTED, "기억 지우기 승인 카드를 띄웠다. 아직 지우지 않았다. 사용자가 카드에서 승인해야 지워진다.");
    }


    private static String preview(String content) {
        int length = content.codePointCount(0, content.length());
        return length <= CONTENT_PREVIEW ? content : content.substring(0, content.offsetByCodePoints(0, CONTENT_PREVIEW)) + "…";
    }


    private static Map<String, Object> result(String status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("message", message);
        return body;
    }
}
