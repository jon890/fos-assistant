package com.bifos.assistant.workspace.application.model;

import java.util.List;

/**
 * 요청자의 실행 공간 상태다.
 *
 * @param available 루트가 설정되었고 디렉터리다
 * @param deletable 지우기 도우미가 설정되었다
 * @param exists 요청자의 디렉터리가 있다
 * @param runningExecutions 사용자 실행 한도가 세는 지금 쥔 자리 수
 * @param agents 요청자가 주인이라 이 공간을 함께 쓰는 에이전트
 */
public record WorkspaceStatus(
        boolean available, boolean deletable, boolean exists, int runningExecutions, List<WorkspaceAgent> agents) {}
