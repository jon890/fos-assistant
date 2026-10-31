package com.bifos.assistant.chat.artifact.infra;

import com.bifos.assistant.chat.artifact.domain.ChatArtifact;
import com.bifos.assistant.crypto.domain.SealedText;
import com.bifos.assistant.crypto.domain.BinaryCipher;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.Sha256;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 보관 본문을 주인의 데이터 key 로 암호화하고 푼다(ADR-20261008 / data-encryption).
 *
 * <p>본문은 바이트로 암호화한다. PR 1 이 글로 암호화한 본문도 같은 key 와 모양이라 그 UTF-8 바이트로 풀린다.
 *
 * <p>암호화가 꺼져 있거나 key 를 쓸 수 없으면 평문으로 두지 않고 거절한다. 태그가 맞지 않는 본문은 일부도 주지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ArtifactVaultBodies {

    private final BinaryCipher cipher;


    /**
     * 보관 본문 하나를 암호화한다.
     *
     * @param bundleId 묶음 뿌리 행의 번호. 뿌리 행과 묶음이 아닌 보관본은 {@code null}
     */
    public SealedText seal(
        Long ownerUserId, Long conversationId, String path, String fingerprint, Long bundleId, byte[] plain) {
        try {
            if (!cipher.enabled()) {
                throw unavailable();
            }
            SealedText sealed = cipher
                .sealBytes(ownerUserId, aad(ownerUserId, conversationId, path, fingerprint, bundleId), plain)
                .orElseThrow(ArtifactVaultBodies::unavailable);
            if (sealed.keyId() == null || sealed.content() == null || sealed.content().isBlank()) {
                throw unavailable();
            }
            return sealed;
        } catch (IllegalStateException ex) {
            throw unavailable();
        }
    }


    public byte[] open(ChatArtifact row) {
        try {
            if (row.vaultBody() == null || row.vaultKeyId() == null || row.vaultUserId() == null) {
                throw unavailable();
            }
            return cipher.openBytes(
                row.vaultKeyId(),
                row.vaultUserId(),
                aad(row.vaultUserId(), row.conversationId(), row.path(), row.vaultFingerprint(), row.vaultBundleId()),
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


    /**
     * 묶음 뿌리의 지문이다. 부르는 파일이 없으면 {@link #fingerprint} 와 같아 PR 1 의 보관본과 한 건으로 묶인다.
     *
     * @param parts 부르는 파일마다 {@code 경로 \0 SHA-256 16진수}. 순서는 부르는 쪽이 경로 순으로 맞춘다
     */
    public static String bundleFingerprint(String rootFingerprint, List<String> parts) {
        if (parts.isEmpty()) {
            return rootFingerprint;
        }
        MessageDigest digest = Sha256.newDigest();
        digest.update(("bundle\0" + rootFingerprint).getBytes(StandardCharsets.UTF_8));
        for (String part : parts) {
            digest.update(("\0" + part).getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }


    /** 묶음 안 파일 하나의 지문이다. 뿌리의 지문과 경로로 정해 같은 사용자 안에서 겹치지 않는다. */
    public static String partFingerprint(String bundleFingerprint, String path) {
        return sha256Hex(("part\0" + bundleFingerprint + "\0" + path).getBytes(StandardCharsets.UTF_8));
    }


    public static String sha256Hex(byte[] body) {
        return HexFormat.of().formatHex(Sha256.newDigest().digest(body));
    }


    /** 암호문의 자리다. 묶음 안 파일은 뿌리 행 번호까지 묶어 다른 묶음으로 옮기면 풀리지 않는다. */
    private static String aad(Long ownerUserId, Long conversationId, String path, String fingerprint, Long bundleId) {
        String base = "chat_artifact_vault:user:" + ownerUserId + ":conversation:" + conversationId + ":path:" + path
            + ":fingerprint:" + fingerprint;
        return bundleId == null ? base : base + ":bundle:" + bundleId;
    }


    private static ApiException unavailable() {
        return new ApiException(ErrorCode.ARTIFACT_VAULT_UNAVAILABLE, "the artifact vault is unavailable");
    }
}
