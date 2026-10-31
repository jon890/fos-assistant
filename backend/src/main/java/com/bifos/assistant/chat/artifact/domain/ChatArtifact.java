package com.bifos.assistant.chat.artifact.domain;

import com.bifos.assistant.shared.domain.CreatedAtEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 에이전트가 한 turn 안에 대화의 결과물 폴더에 만들거나 고친 HTML 파일 하나다.
 *
 * <p>본문은 파일로 두고 이 행은 그 파일을 가리키기만 한다. 행을 지우지 않는다. 보관 기간이 지나 파일을 지우면
 * {@code deletedAt} 을 적어, 지난 대화에 그 자리에 파일이 있었다는 것을 남긴다. 근거는 ADR-027 에 있다.
 */
@Entity
@Table(name = "chat_artifact")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Accessors(fluent = true)
public class ChatArtifact extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Getter
    private Long id;

    /** 어느 대화의 폴더인가. 폴더 이름이기도 하다. */
    @Column(name = "conversation_id", nullable = false)
    @Getter
    private Long conversationId;

    /** 이 파일을 만든 turn 의 답 메시지. 보관 사본 행은 비어 있다. */
    @Column(name = "message_id")
    @Getter
    private Long messageId;

    /** 대화 폴더 안의 상대 경로. {@code /} 로 나눈다. */
    @Column(name = "path", nullable = false, length = 500)
    @Getter
    private String path;

    /** 찾았을 때의 크기. */
    @Column(name = "byte_size", nullable = false)
    @Getter
    private long byteSize;

    /** 보관 기간이 지나 파일을 지운 시각. 비어 있으면 아직 파일이 있다. */
    @Column(name = "deleted_at")
    @Getter
    private Instant deletedAt;

    /** 보관 사본의 주인(대화 요청자). 비어 있으면 대화 결과물 행이다. */
    @Column(name = "vault_user_id")
    @Getter
    private Long vaultUserId;

    /** 보관 본문을 암호화한 데이터 key 의 번호. */
    @Column(name = "vault_key_id")
    @Getter
    private Long vaultKeyId;

    /** 암호화한 보관 본문. 평문은 어디에도 두지 않는다. */
    @Column(name = "vault_body", columnDefinition = "LONGTEXT")
    @Getter
    private String vaultBody;

    /** 같은 사용자의 같은 보관 요청을 한 건으로 만드는 지문. */
    @Column(name = "vault_fingerprint", length = 64)
    @Getter
    private String vaultFingerprint;


    private ChatArtifact(Long conversationId, Long messageId, String path, long byteSize) {
        this.conversationId = conversationId;
        this.messageId = messageId;
        this.path = path;
        this.byteSize = byteSize;
    }


    private ChatArtifact(
        Long conversationId, String path, long byteSize, Long vaultUserId, Long vaultKeyId, String vaultBody,
        String vaultFingerprint) {
        this.conversationId = conversationId;
        this.path = path;
        this.byteSize = byteSize;
        this.vaultUserId = vaultUserId;
        this.vaultKeyId = vaultKeyId;
        this.vaultBody = vaultBody;
        this.vaultFingerprint = vaultFingerprint;
    }


    /** 사용자가 골라 보관한 사본이다. 답에 묶이지 않고 대화를 지워도 남는다. */
    public static ChatArtifact vaulted(
        Long conversationId, String path, long byteSize, Long ownerUserId, Long keyId, String sealedBody,
        String fingerprint) {
        return new ChatArtifact(conversationId, path, byteSize, ownerUserId, keyId, sealedBody, fingerprint);
    }


    public static ChatArtifact of(Long conversationId, Long messageId, String path, long byteSize) {
        return new ChatArtifact(conversationId, messageId, path, byteSize);
    }


    public boolean isVaulted() {
        return vaultUserId != null;
    }


    public boolean isDeleted() {
        return deletedAt != null;
    }
}
