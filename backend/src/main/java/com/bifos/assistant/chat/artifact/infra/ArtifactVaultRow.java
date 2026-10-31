package com.bifos.assistant.chat.artifact.infra;

import java.time.Instant;

/** 보관 목록의 한 줄이다. 묶음이면 뿌리 행이고 묶음 안 파일의 수와 바이트를 더해 준다. 암호화한 본문은 읽지 않는다. */
public interface ArtifactVaultRow {

    Long getId();


    String getPath();


    long getByteSize();


    Instant getCreatedAt();


    /** 묶음 안에서 뿌리가 부르는 파일의 수. 묶음이 아니면 0 이다. */
    long getPartCount();


    /** 묶음 안에서 뿌리가 부르는 파일의 평문 바이트 수 합. */
    long getPartBytes();
}
