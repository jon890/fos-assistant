package com.bifos.assistant.chat.artifact.application;

import com.bifos.assistant.chat.artifact.domain.VaultLabel;
import com.bifos.assistant.chat.artifact.domain.type.VaultOrigin;
import com.bifos.assistant.chat.artifact.infra.ArtifactVaultMeta;
import com.bifos.assistant.chat.artifact.infra.ArtifactVaultMetaRow;
import com.bifos.assistant.chat.artifact.infra.ChatArtifactRepository;
import com.bifos.assistant.crypto.domain.SealedText;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 보관할 파일의 제목과 설명을 정한다(ADR-20261119 / file-space-put, 스펙 workspace REQ6).
 *
 * <p>부르는 쪽이 준 값을 먼저 쓰고, 없으면 {@code artifact_write} 가 그 대화·경로의 결과물 행에 둔 값을 쓴다.
 * 제목이 같은 대화의 다른 파일과 겹치면 뒤에 번호를 붙인다. {@code ArtifactVaultService} 의 사용자별 잠금 안에서 부른다.
 */
@Component
@RequiredArgsConstructor
class ArtifactVaultTitles {

    private final ChatArtifactRepository rows;
    private final ArtifactVaultMeta metas;


    /**
     * 뿌리 행에 둘 제목·설명의 암호문이다. 제목도 설명도 없거나 암호화를 쓸 수 없으면 빈 값이다.
     *
     * @param origin 들어온 길. 자동 보관과 「보관」 단추는 null
     * @param path 뿌리 행에 적을 경로
     */
    Optional<SealedText> sealFor(
        Long ownerUserId, Long conversationId, VaultOrigin origin, String path, String title, String description) {
        Optional<VaultLabel> given = VaultLabel.of(title, description);
        // 실행 공간의 경로는 대화 폴더의 경로가 아니라 결과물 행의 제목을 물려받지 않는다.
        Optional<VaultLabel> meta = given.isPresent() || origin == VaultOrigin.PUT_WORKSPACE
            ? given
            : writtenTitle(ownerUserId, conversationId, path);
        return meta
            .map(value -> value.title() == null
                ? value
                : value.withTitle(unique(ownerUserId, conversationId, origin, path, value.title())))
            .flatMap(value -> metas.seal(ownerUserId, conversationId, path, value));
    }


    /** {@code artifact_write} 가 그 경로에 둔 가장 새 제목이다. */
    private Optional<VaultLabel> writtenTitle(Long ownerUserId, Long conversationId, String path) {
        return rows.findTitledArtifacts(conversationId, path).stream()
            .findFirst()
            .flatMap(row -> metas.open(ownerUserId, conversationId, path, row.getVaultMeta(), row.getVaultMetaKeyId()));
    }


    /**
     * 같은 대화의 살아 있는 보관본과 겹치지 않는 제목이다. 같은 경로와 같은 길로 들어온 앞 보관본은 겹침에서 뺀다.
     * 같은 파일을 고쳐 다시 보관할 때마다 번호가 늘지 않게 한다.
     */
    private String unique(Long ownerUserId, Long conversationId, VaultOrigin origin, String path, String title) {
        Set<String> taken = new HashSet<>();
        for (ArtifactVaultMetaRow row : rows.findVaultTitles(ownerUserId, conversationId)) {
            if (row.getPath().equals(path) && row.getVaultOrigin() == origin) {
                continue;
            }
            metas.open(ownerUserId, conversationId, row.getPath(), row.getVaultMeta(), row.getVaultMetaKeyId())
                .map(VaultLabel::title)
                .ifPresent(taken::add);
        }
        String name = title;
        int number = 2;
        while (taken.contains(name)) {
            name = title + " " + number;
            number++;
        }
        return name;
    }
}
