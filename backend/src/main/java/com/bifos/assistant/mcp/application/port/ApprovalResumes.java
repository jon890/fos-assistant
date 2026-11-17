package com.bifos.assistant.mcp.application.port;

import com.bifos.assistant.shared.auth.CurrentUser;

/**
 * 에이전트가 사람 화면에서 멈춘 승인한 작업을 같은 승인으로 이어 가는 길이다(docs/features/browser-cowork/spec.md 의 REQ8, REQ9).
 * 구현은 커넥터가 갖고 mcp 는 커넥터를 import 하지 않는다.
 */
public interface ApprovalResumes {

    /**
     * 그 대화의 살아 있는 사람 기다림 승인을 같은 승인으로 한 번 더 실행하고, 모델에게 줄 글을 돌려준다. 다른 대화의 승인은 고르지 않는다.
     *
     * @param conversationId 호출한 실행의 대화. 대화 밖 실행이면 null
     */
    String resume(CurrentUser user, Long conversationId);
}
