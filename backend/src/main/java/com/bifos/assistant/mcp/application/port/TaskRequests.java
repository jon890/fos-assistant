package com.bifos.assistant.mcp.application.port;

import com.bifos.assistant.mcp.application.dto.TaskToolInput;
import com.bifos.assistant.mcp.application.dto.TaskToolResult;
import com.bifos.assistant.shared.auth.CurrentUser;

/**
 * 에이전트가 요청자의 예약 작업을 읽고 만들고 고치고 멈추고 지우기를 청하는 길이다(스펙 service-concepts REQ7-REQ10). 구현은
 * {@code task} 가 갖고 mcp 는 task 를 import 하지 않는다.
 *
 * <p>계약은 {@code docs/features/service-concepts/design.md} 의 「PR2 예약 작업 도구」 가 갖는다. 요청자 자신의 일반 예약 작업만
 * 다룬다. 실패는 예외가 아니라 결과의 {@code error} 와 까닭이다.
 *
 * @see TaskToolResult
 */
public interface TaskRequests {

    TaskToolResult list(CurrentUser user);


    /** @param originExecutionId 호출자가 검증한 현재 실행 번호. 작업의 에이전트와 대화를 정한다 */
    TaskToolResult create(CurrentUser user, Long originExecutionId, TaskToolInput input);


    /** @param task 작업 번호나 이름 */
    TaskToolResult update(CurrentUser user, String task, TaskToolInput changes);


    /** @param paused 참이면 멈추고 거짓이면 다시 켠다 */
    TaskToolResult setPaused(CurrentUser user, String task, boolean paused);


    /** 지우지 않고 승인 카드를 띄운다. */
    TaskToolResult requestDelete(CurrentUser user, Long originExecutionId, String task);
}
