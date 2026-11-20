package com.bifos.assistant.crypto.domain.entity;

import com.bifos.assistant.shared.domain.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.springframework.data.domain.Persistable;

/**
 * 서버 비밀값 한 줄이다(ADR-20261120 / server-secret).
 *
 * <p>값은 KEK 로 감싼 암호문이다. 값을 지워도 줄은 남긴다. 줄이 있다는 것이 「옛 설정 가져오기를 이미 했다」 는 표지다.
 */
@Entity
@Table(name = "server_secret")
@Accessors(fluent = true)
@Getter
public class ServerSecret extends AuditedEntity implements Persistable<String> {

    @Id
    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "kek_id", length = 32)
    private String kekId;

    /** {@code v1.<IV>.<암호문과 태그>}. 값이 비었으면 null */
    @Column(name = "sealed_value", length = 4096)
    private String sealedValue;

    @Column(name = "prefix", length = 16)
    private String prefix;

    @Column(name = "meta_json", columnDefinition = "TEXT")
    private String metaJson;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Transient
    private boolean persisted;


    protected ServerSecret() {}


    private ServerSecret(String name) {
        this.name = name;
    }


    public static ServerSecret named(String name) {
        return new ServerSecret(name);
    }


    /** 값을 감싼 채로 둔다 */
    public void seal(String kekId, String sealedValue, String prefix, String metaJson, Long updatedBy) {
        this.kekId = kekId;
        this.sealedValue = sealedValue;
        this.prefix = prefix;
        this.metaJson = metaJson;
        this.updatedBy = updatedBy;
    }


    /** 값을 비운다. 부가 값도 함께 비운다 */
    public void clear(Long updatedBy) {
        seal(null, null, null, null, updatedBy);
    }


    public boolean set() {
        return sealedValue != null;
    }


    @Override
    public String getId() {
        return name();
    }


    @Override
    public boolean isNew() {
        return !persisted;
    }


    @PostLoad
    @PostPersist
    void markPersisted() {
        persisted = true;
    }
}
