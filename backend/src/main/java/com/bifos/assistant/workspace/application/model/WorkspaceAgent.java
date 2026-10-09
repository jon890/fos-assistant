package com.bifos.assistant.workspace.application.model;

/**
 * 공간을 함께 쓰는 에이전트 하나다.
 *
 * @param shared 그룹에 공개했다. 다른 사용자의 실행이 만든 파일도 이 공간에 생긴다
 */
public record WorkspaceAgent(String code, String name, boolean shared) {}
