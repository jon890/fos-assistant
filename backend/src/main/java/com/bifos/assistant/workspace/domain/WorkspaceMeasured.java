package com.bifos.assistant.workspace.domain;

/**
 * 실행 공간 디렉터리 하나를 센 결과다. 파일 이름과 경로는 담지 않는다.
 *
 * @param bytes 일반 파일 크기의 합. 링크와 디렉터리는 더하지 않는다
 * @param entries 디렉터리 자신을 뺀 그 아래의 항목 수. 링크와 읽지 못한 항목도 하나로 센다
 * @param partial 상한에 닿아 멈췄거나 읽지 못한 디렉터리가 있어 일부만 셌다
 */
public record WorkspaceMeasured(long bytes, long entries, boolean partial) {}
