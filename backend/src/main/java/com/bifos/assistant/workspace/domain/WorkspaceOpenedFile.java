package com.bifos.assistant.workspace.domain;

import java.io.InputStream;
import java.time.Instant;

/**
 * 링크를 따라가지 않고 연 일반 파일이다. 스트림은 받은 쪽이 닫는다.
 *
 * @param size 연 뒤에 확인한 크기
 */
public record WorkspaceOpenedFile(long size, Instant modifiedAt, InputStream body) {}
