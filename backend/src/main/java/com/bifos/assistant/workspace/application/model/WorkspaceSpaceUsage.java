package com.bifos.assistant.workspace.application.model;

/**
 * 관리자가 보는 실행 공간 하나의 용량이다. 파일 이름과 경로는 담지 않는다.
 *
 * @param id 디렉터리 이름의 번호. 사용자 번호나 에이전트 번호다
 * @param name 사용자 이름이나 에이전트 이름. 찾지 못하면 {@code null} 이다
 * @param bytes 일반 파일 크기의 합
 * @param entries 항목 수
 * @param partial 일부만 셌다. 요청 전체의 시간을 넘겨 세지 않은 공간도 참이다
 */
public record WorkspaceSpaceUsage(
        WorkspaceSpaceKind kind, long id, String name, long bytes, long entries, boolean partial) {}
