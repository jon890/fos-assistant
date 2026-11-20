package com.bifos.assistant.connector.action.application;

import com.bifos.assistant.connector.action.application.dto.ControlPlaneActionOutcome;
import com.bifos.assistant.connector.action.application.port.ControlPlaneActionRunner;
import com.bifos.assistant.connector.action.domain.ControlPlaneActions;
import com.bifos.assistant.memory.application.MemoryForgetting;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 사용자가 승인한 기억 지우기를 한다(스펙 service-concepts REQ13). 권한은 승인하는 때에 다시 본다. 카드를 띄운 뒤 관리자가 아니게 된
 * 사용자는 그룹 기억을 지우지 못한다. 카드를 띄운 뒤 고친 기억도 지우지 않는다. 사용자가 카드에서 본 내용과 지울
 * 내용이 달라지기 때문이다.
 */
@Component
@RequiredArgsConstructor
class MemoryForgetRunner implements ControlPlaneActionRunner {
    private final MemoryForgetting forgetting;


    @Override
    public String toolName() {
        return ControlPlaneActions.MEMORY_FORGET;
    }


    @Override
    public ControlPlaneActionOutcome run(CurrentUser user, Map<String, String> args) {
        String title = args.getOrDefault("memory", "");
        long memoryId;
        int revision;
        try {
            memoryId = Long.parseLong(args.getOrDefault("memory_id", ""));
            revision = Integer.parseInt(args.getOrDefault("revision", ""));
        } catch (NumberFormatException ex) {
            return ControlPlaneActionOutcome.failed("「" + title + "」 기억을 찾지 못해 지우지 않았어요");
        }
        return switch (forgetting.forget(user, memoryId, revision)) {
            case FORGOTTEN -> ControlPlaneActionOutcome.done("「" + title + "」 기억을 지웠어요");
            case CHANGED -> ControlPlaneActionOutcome.failed(
                "「" + title + "」 기억이 그 사이 바뀌어 지우지 않았어요. 지우려면 다시 요청해 주세요");
            case GONE -> ControlPlaneActionOutcome.failed("「" + title + "」 기억이 이미 없거나 지울 수 없어 그대로 뒀어요");
        };
    }
}
