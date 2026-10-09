package com.bifos.assistant.workspace.domain;

import java.util.List;

/**
 * 디렉터리 하나의 목록이다. 디렉터리를 먼저, 그다음 이름 순서다.
 *
 * @param path 주인 디렉터리 안의 상대 경로. 주인 디렉터리는 빈 문자열이다
 * @param truncated 상한보다 많은 항목이 있어 일부만 실었다
 */
public record WorkspaceListing(String path, List<WorkspaceEntry> entries, boolean truncated) {}
