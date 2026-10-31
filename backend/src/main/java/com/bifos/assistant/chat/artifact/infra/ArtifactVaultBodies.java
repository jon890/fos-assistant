package com.bifos.assistant.chat.artifact.infra;

import com.bifos.assistant.chat.artifact.domain.ChatArtifact;
import com.bifos.assistant.crypto.domain.SealedText;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.Sha256;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 보관 본문을 주인의 데이터 key 로 암호화하고 푼다(ADR-20261008 / data-encryption).
 *
 * <p>암호화가 꺼져 있거나 key 를 쓸 수 없으면 평문으로 두지 않고 거절한다. 태그가 맞지 않는 본문은 일부도 주지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ArtifactVaultBodies {

    private final TextCipher cipher;


    public SealedText seal(Long ownerUserId, Long conversationId, String path, String fingerprint, String plain) {
        try {
            if (!cipher.enabled()) {
                throw unavailable();
            }
            SealedText sealed = cipher.seal(ownerUserId, aad(ownerUserId, conversationId, path, fingerprint), plain)
                .orElseThrow(ArtifactVaultBodies::unavailable);
            if (sealed.keyId() == null || sealed.content() == null || sealed.content().isBlank()) {
                throw unavailable();
            }
            return sealed;
        } catch (IllegalStateException ex) {
            throw unavailable();
        }
    }


    public String open(ChatArtifact row) {
        try {
            if (row.vaultBody() == null || row.vaultKeyId() == null || row.vaultUserId() == null) {
                throw unavailable();
            }
            return cipher.open(
                row.vaultKeyId(),
                row.vaultUserId(),
                aad(row.vaultUserId(), row.conversationId(), row.path(), row.vaultFingerprint()),
                row.vaultBody()).orElseThrow(ArtifactVaultBodies::unavailable);
        } catch (IllegalStateException ex) {
            throw unavailable();
        }
    }


    /** 같은 사용자의 같은 대화·경로·본문이면 같은 값이다. 사용자 사이에 비교하지 않는다. */
    public static String fingerprint(Long conversationId, String path, byte[] body) {
        MessageDigest digest = Sha256.newDigest();
        digest.update((conversationId + "\0" + path + "\0").getBytes(StandardCharsets.UTF_8));
        digest.update(body);
        return HexFormat.of().formatHex(digest.digest());
    }


    private static String aad(Long ownerUserId, Long conversationId, String path, String fingerprint) {
        return "chat_artifact_vault:user:" + ownerUserId + ":conversation:" + conversationId + ":path:" + path
            + ":fingerprint:" + fingerprint;
    }


    private static ApiException unavailable() {
        return new ApiException(ErrorCode.ARTIFACT_VAULT_UNAVAILABLE, "the artifact vault is unavailable");
    }
}
