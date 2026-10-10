package com.bifos.assistant.workspace.domain;

import java.util.List;

/**
 * 디렉터리 하나의 목록이다. 디렉터리를 먼저, 그다음 이름 순서다.
 *
 * @param path 주인 디렉터리 안의 상대 경로. 주인 디렉터리는 빈 문자열이다
 * @param truncated 다음 페이지가 있다
 * @param nextCursor 표시한 마지막 항목의 정렬 키. 끝이면 null이다
 */
public record WorkspaceListing(String path, List<WorkspaceEntry> entries, boolean truncated, String nextCursor) {

    public WorkspaceListing(String path, List<WorkspaceEntry> entries, boolean truncated) {
        this(path, entries, truncated, null);
    }
}
