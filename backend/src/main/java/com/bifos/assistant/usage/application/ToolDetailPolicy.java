package com.bifos.assistant.usage.application;

import com.bifos.assistant.shared.auth.CurrentUser;
import java.util.Set;

/**
 * 도구 사건의 {@code detail} 을 보는 사람에게 실어 보낼지 정한다.
 *
 * <p>명령 원문은 {@code ADMIN} 역할에게만 보낸다. {@code MEMBER} 역할에게는 검색어와 사진에 던진 질문처럼
 * 사람 말로 쓰인 도구만 보낸다. 근거는 ADR-038 에 있다.
 *
 * <p>숨길 도구가 아니라 보일 도구를 적는다. 새 도구가 생기면 기본으로 숨게 하기 위해서다. 도구 이름은
 * 전체로 비교한다. 다른 MCP 서버가 같은 이름을 붙인 도구는 공개하지 않는다.
 *
 * <p>대화 스트림과 실행 나무 조회가 이 판정을 함께 쓴다. 저장은 바꾸지 않고 응답을 만들 때만 뺀다.
 */
public final class ToolDetailPolicy {

    /** {@code MEMBER} 역할에게도 {@code detail} 을 싣는 도구다. */
    static final Set<String> PUBLIC_TOOLS = Set.of("web_search", "vision_analyze");

    private ToolDetailPolicy() {
    }

    /** 이 사람에게 이 도구의 {@code detail} 을 실어도 되는가. 도구 이름이 없으면 싣지 않는다. */
    public static boolean visibleTo(CurrentUser viewer, String toolName) {
        if (viewer.isAdmin()) {
            return true;
        }
        return toolName != null && PUBLIC_TOOLS.contains(toolName);
    }
}
