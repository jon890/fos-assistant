package com.bifos.assistant.chat.artifact.infra;

import com.bifos.assistant.chat.artifact.domain.VaultLabel;
import com.bifos.assistant.crypto.domain.SealedText;
import com.bifos.assistant.crypto.domain.TextCipher;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 「내 파일」 의 제목과 설명을 주인의 데이터 key 로 암호화하고 푼다(ADR-20261119 / file-space-put).
 *
 * <p>제목은 대화 내용을 드러낼 수 있어 보관 본문과 같은 key 로 암호화한다. <b>평문으로 두는 길은 없다.</b> 암호화를 쓸 수 없으면
 * 빈 값을 주고 부르는 쪽이 제목을 버린다.
 *
 * <p>암호문의 자리는 사용자, 대화 번호, 경로로 묶는다. 다른 행으로 옮기면 풀리지 않는다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ArtifactVaultMeta {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final TextCipher cipher;


    /** 암호화한다. 암호화를 쓸 수 없으면 빈 값이다. */
    public Optional<SealedText> seal(Long ownerUserId, Long conversationId, String path, VaultLabel meta) {
        if (!cipher.enabled()) {
            return Optional.empty();
        }
        String json = JSON.writeValueAsString(JSON.createObjectNode()
            .put("title", meta.title())
            .put("description", meta.description()));
        try {
            return cipher.seal(ownerUserId, aad(ownerUserId, conversationId, path), json)
                .filter(sealed -> sealed.keyId() != null);
        } catch (IllegalStateException ex) {
            return Optional.empty();
        }
    }


    /** 푼다. 값이 없거나 풀지 못하면 빈 값이다. 목록 한 줄이 풀리지 않아도 목록 전체를 막지 않는다. */
    public Optional<VaultLabel> open(Long ownerUserId, Long conversationId, String path, String sealed, Long keyId) {
        if (sealed == null || keyId == null) {
            return Optional.empty();
        }
        try {
            return cipher.open(keyId, ownerUserId, aad(ownerUserId, conversationId, path), sealed).flatMap(json -> {
                JsonNode node = JSON.readTree(json);
                return VaultLabel.of(text(node, "title"), text(node, "description"));
            });
        } catch (IllegalStateException | JacksonException ex) {
            log.warn("could not open the title of a kept file userId={} error={}", ownerUserId, ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }


    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.asString();
    }


    private static String aad(Long ownerUserId, Long conversationId, String path) {
        return "chat_artifact_meta:user:" + ownerUserId + ":conversation:" + conversationId + ":path:" + path;
    }
}
