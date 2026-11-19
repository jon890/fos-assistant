package com.bifos.assistant.chat.artifact.infra;

import java.time.Instant;

/** 넣기 도구가 넣고 답에 묶인 보관 뿌리 행의 일부다. 암호화한 본문({@code vault_body})은 읽지 않는다. */
public interface ArtifactPutFileRow {

    Long getId();


    /** 이 파일을 내준 답 메시지. */
    Long getMessageId();


    Long getOwnerUserId();


    Long getConversationId();


    /** 지웠으면 빈 글이다. */
    String getPath();


    long getByteSize();


    /** 묶음 안 파일의 바이트 합. 묶음이 아니면 0 이다. */
    long getPartBytes();


    Instant getDeletedAt();


    String getVaultMeta();


    Long getVaultMetaKeyId();
}
