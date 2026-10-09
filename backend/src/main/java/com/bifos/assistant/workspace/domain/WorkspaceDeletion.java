package com.bifos.assistant.workspace.domain;

/**
 * 지우기의 결과다.
 *
 * @param entries 지운 항목 수
 * @param bytes 지운 일반 파일의 크기 합
 */
public record WorkspaceDeletion(WorkspaceEntryKind kind, long entries, long bytes) {}
