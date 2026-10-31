package com.bifos.assistant.chat.artifact.infra;

import java.time.Instant;

/** 보관 목록의 한 줄이다. 암호화한 본문은 읽지 않는다. */
public interface ArtifactVaultRow {

    Long getId();


    String getPath();


    long getByteSize();


    Instant getCreatedAt();
}
