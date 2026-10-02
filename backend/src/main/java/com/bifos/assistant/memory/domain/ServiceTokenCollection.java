package com.bifos.assistant.memory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/** 서비스 토큰이 받는 collection 하나다. 에이전트의 {@code agent_memory_collection} 과 같은 뜻이다(ADR-056). */
@Entity
@Table(name = "service_token_collection")
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServiceTokenCollection {

    @EmbeddedId
    private ServiceTokenCollectionId id;

    /** 참이면 이 collection 의 민감 문서까지 읽는다. */
    @Column(name = "allow_sensitive", nullable = false)
    private boolean allowSensitive;

    private ServiceTokenCollection(Long tokenId, String collection, boolean allowSensitive) {
        this.id = new ServiceTokenCollectionId(tokenId, collection);
        this.allowSensitive = allowSensitive;
    }

    public static ServiceTokenCollection of(Long tokenId, String collection, boolean allowSensitive) {
        return new ServiceTokenCollection(tokenId, collection, allowSensitive);
    }

    public String collection() {
        return id.collection();
    }
}
