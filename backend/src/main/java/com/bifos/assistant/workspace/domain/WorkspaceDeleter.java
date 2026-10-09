package com.bifos.assistant.workspace.domain;

/**
 * 주인 디렉터리 안의 경로 하나를 지운다. 계약은 {@code backend/docs/code-architecture.md} 의 「지우기」 가 갖는다.
 *
 * <p>실패는 {@code ApiException} 으로 던진다. 도우미의 실패 코드와 응답의 대응은 그 문서의 표다.
 */
public interface WorkspaceDeleter {

    /**
     * @param owner 요청자로 만든 주인 키. 요청 값으로 정하지 않는다
     * @param path 주인 디렉터리 자체가 아닌 경로
     * @param maxEntries 디렉터리 안의 항목이 이보다 많으면 아무것도 지우지 않는다
     */
    WorkspaceDeletion delete(String owner, WorkspacePath path, int maxEntries);
}
