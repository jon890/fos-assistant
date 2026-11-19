package com.bifos.assistant.chat.artifact.infra;

import com.bifos.assistant.chat.artifact.domain.type.VaultOrigin;
import java.time.Instant;

/** 제목과 설명을 풀 때 쓰는 행의 일부다. 암호화한 본문({@code vault_body})은 읽지 않는다. */
public interface ArtifactVaultMetaRow {

    Long getId();


    String getPath();


    VaultOrigin getVaultOrigin();


    String getVaultMeta();


    Long getVaultMetaKeyId();


    /** 지운 시각. 지문으로 찾은 행이 지우기 이력인지 볼 때 쓴다. */
    Instant getDeletedAt();


    Instant getCreatedAt();
}
