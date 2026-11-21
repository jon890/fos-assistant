package com.bifos.assistant.chat.artifact.domain;

import com.bifos.assistant.chat.artifact.domain.type.AutoVaultState;
import com.bifos.assistant.chat.artifact.domain.type.VaultOrigin;
import com.bifos.assistant.shared.domain.CreatedAtEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 *
 * <p>보관 사본 행({@code vaultUserId} 가 채워진 행)의 {@code deletedAt} 은 사용자가 그 보관본을 지운 시각이다. 지운 뿌리 행은
 * 본문과 경로를 비우고 지문만 남겨, 자동 보관이 같은 보관본을 다시 만들지 못하게 한다(ADR-20261101 / artifact-vault).
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

    /** 이 보관 사본이 든 묶음의 뿌리 행 번호. 뿌리 행과 묶음이 아닌 보관 사본, 대화 결과물 행은 비어 있다. */
    @Column(name = "vault_bundle_id")
    @Getter
    private Long vaultBundleId;

    /** 결과물 행의 자동 보관 상태. 비어 있으면 자동 보관 대상이 아니다. 보관 사본 행은 비어 있다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "auto_vault_state", length = 16)
    @Getter
    private AutoVaultState autoVaultState;

    /** 자동 보관이 실패한 까닭의 오류 코드. */
    @Column(name = "auto_vault_error", length = 64)
    @Getter
    private String autoVaultError;

    /** 자동 보관을 시도한 횟수. */
    @Column(name = "auto_vault_attempts", nullable = false)
    @Getter
    private int autoVaultAttempts;

    /**
     * 제목과 설명의 JSON 을 주인의 데이터 key 로 암호화한 글(ADR-20261119 / file-space-put). 보관 뿌리 행과,
     * {@code artifact_write} 가 제목을 받은 결과물 행에 있다.
     */
    @Column(name = "vault_meta", columnDefinition = "LONGTEXT")
    @Getter
    private String vaultMeta;

    /** {@link #vaultMeta} 를 암호화한 데이터 key 의 번호. */
    @Column(name = "vault_meta_key_id")
    @Getter
    private Long vaultMetaKeyId;

    /** 보관 행이 들어온 길. 비어 있으면 자동 보관이나 「보관」 단추다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "vault_origin", length = 16)
    @Getter
    private VaultOrigin vaultOrigin;

    /** 넣기 도구가 넣은 보관 뿌리 행을 내준 답 메시지. turn 이 끝날 때 채운다. */
    @Column(name = "vault_message_id")
    @Getter
    private Long vaultMessageId;

    /** 비서가 이미지 도구에 넘긴 그림 지시문. {@link #generationPromptKeyId} 가 있으면 암호문이다. 만든 그림 행에만 있다. */
    @Column(name = "generation_prompt", columnDefinition = "LONGTEXT")
    @Getter
    private String generationPrompt;

    /** 지시문을 암호화한 데이터 key 의 번호. 비어 있으면 지시문은 평문이다. */
    @Column(name = "generation_prompt_key_id")
    @Getter
    private Long generationPromptKeyId;

    /**
     * 그림을 만들 때 참고한 링크 주소를 줄바꿈으로 이은 글. {@link #generationSourceLinksKeyId} 가 있으면 암호문이다.
     * 링크를 쓰지 않은 그림은 비어 있다(ADR-20261120 / link-image-source).
     */
    @Column(name = "generation_source_links", columnDefinition = "LONGTEXT")
    @Getter
    private String generationSourceLinks;

    /** 참고한 링크를 암호화한 데이터 key 의 번호. 비어 있으면 평문이다. 지시문의 key 와 따로 둔다. */
    @Column(name = "generation_source_links_key_id")
    @Getter
    private Long generationSourceLinksKeyId;

    /**
     * {@link #generationSourceLinks} 의 링크마다 비서가 찾은 것이면 {@code 1}, 사용자가 보낸 것이면 {@code 0} 을 같은 순서로 이은 글.
     * 찾은 링크가 없으면 비어 있다(ADR-20261121 / found-link-image-source).
     */
    @Column(name = "generation_found_link_flags", length = 16)
    @Getter
    private String generationFoundLinkFlags;

    /** 같은 올리기가 다시 와도 한 행이 되게 하는 값. 만든 그림 행에만 있다. */
    @Column(name = "generation_call_key", length = 64, columnDefinition = "CHAR(64)")
    @Getter
    private String generationCallKey;


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


    /**
     * 묶음 뿌리가 부르는 파일의 보관 사본이다. 뿌리 행과 같은 주인, 같은 대화이고 뿌리를 지우면 함께 지운다.
     *
     * @param bundleId 뿌리 행의 번호
     */
    public static ChatArtifact vaultedPart(
        Long conversationId, String path, long byteSize, Long ownerUserId, Long keyId, String sealedBody,
        String fingerprint, Long bundleId) {
        ChatArtifact part = new ChatArtifact(conversationId, path, byteSize, ownerUserId, keyId, sealedBody, fingerprint);
        part.vaultBundleId = bundleId;
        return part;
    }


    /**
     * 보관 뿌리 행에 들어온 길과 제목·설명의 암호문을 적는다. 행을 넣기 전에만 부른다.
     *
     * @param meta 암호화한 제목·설명. 없으면 null
     */
    public ChatArtifact withVaultMeta(VaultOrigin origin, String meta, Long metaKeyId) {
        this.vaultOrigin = origin;
        this.vaultMeta = meta;
        this.vaultMetaKeyId = metaKeyId;
        return this;
    }


    /**
     * {@code artifact_write} 가 제목을 받은 HTML 의 결과물 행이다. 답 메시지는 turn 이 끝날 때 묶는다.
     *
     * @param meta 암호화한 제목·설명
     */
    public static ChatArtifact titled(Long conversationId, String path, long byteSize, String meta, Long metaKeyId) {
        ChatArtifact titled = new ChatArtifact(conversationId, null, path, byteSize);
        titled.vaultMeta = meta;
        titled.vaultMetaKeyId = metaKeyId;
        return titled;
    }


    public static ChatArtifact of(Long conversationId, Long messageId, String path, long byteSize) {
        return new ChatArtifact(conversationId, messageId, path, byteSize);
    }


    /**
     * fos-ctx 가 올린 그림이다. 답 메시지는 turn 이 끝날 때 묶는다(ADR-20261119 / generated-image).
     *
     * @param prompt 저장할 지시문. 암호화했으면 암호문이다
     * @param promptKeyId 지시문을 암호화한 key 번호. 평문이면 null
     * @param sourceLinks 참고한 링크를 저장할 글. 암호화했으면 암호문이다. 링크가 없으면 null
     * @param sourceLinksKeyId 링크를 암호화한 key 번호. 평문이거나 링크가 없으면 null
     * @param foundLinkFlags 링크마다 비서가 찾았는지 적은 글. 찾은 링크가 없으면 null
     */
    public static ChatArtifact generatedImage(
        Long conversationId,
        String path,
        long byteSize,
        String prompt,
        Long promptKeyId,
        String sourceLinks,
        Long sourceLinksKeyId,
        String foundLinkFlags,
        String callKey) {
        ChatArtifact image = new ChatArtifact(conversationId, null, path, byteSize);
        image.generationPrompt = prompt;
        image.generationPromptKeyId = promptKeyId;
        image.generationSourceLinks = sourceLinks;
        image.generationSourceLinksKeyId = sourceLinksKeyId;
        image.generationFoundLinkFlags = foundLinkFlags;
        image.generationCallKey = callKey;
        return image;
    }


    /** 비서가 만든 그림 행인가. HTML 이 부르는 사진은 행이 없으므로 올리기가 만든 행만 참이다. */
    public boolean isGeneratedImage() {
        return generationCallKey != null;
    }


    public boolean isVaulted() {
        return vaultUserId != null;
    }


    public boolean isDeleted() {
        return deletedAt != null;
    }
}
