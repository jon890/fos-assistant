package com.bifos.assistant.workspace.domain;

import java.time.Instant;

/**
 * 실행 공간 목록의 한 줄이다.
 *
 * @param size {@link WorkspaceEntryKind#FILE} 만 채운다
 * @param readable 본문이나 목록을 줄 수 있는지다. 링크와 특수 파일, 하드 링크가 둘 이상인 파일은 거짓이다
 * @param openable 이름을 본문 주소의 조각으로 쓸 수 있는지다
 */
public record WorkspaceEntry(
        String name, WorkspaceEntryKind kind, Long size, Instant modifiedAt, boolean readable, boolean openable) {}
