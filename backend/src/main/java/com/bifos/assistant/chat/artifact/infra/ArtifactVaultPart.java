package com.bifos.assistant.chat.artifact.infra;

/** 묶음 안 파일 하나의 번호와 경로다. 내려받을 때 본문을 하나씩 풀려고 먼저 목록만 읽는다. */
public interface ArtifactVaultPart {

    Long getId();


    String getPath();
}
