package com.bifos.assistant.chat.artifact.application;

import com.bifos.assistant.chat.artifact.application.dto.ArtifactKept;
import com.bifos.assistant.chat.artifact.application.dto.PutFileDetail;
import com.bifos.assistant.chat.artifact.config.ArtifactVaultProperties;
import com.bifos.assistant.chat.artifact.domain.ArtifactReferences;
import com.bifos.assistant.chat.artifact.domain.VaultFileType;
import com.bifos.assistant.chat.artifact.domain.VaultLabel;
import com.bifos.assistant.chat.artifact.domain.type.VaultOrigin;
import com.bifos.assistant.chat.artifact.infra.ArtifactPutFileRow;
import com.bifos.assistant.chat.artifact.infra.ArtifactStore;
import com.bifos.assistant.chat.artifact.infra.ArtifactVaultMeta;
import com.bifos.assistant.chat.artifact.infra.ChatArtifactRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 에이전트의 넣기 도구가 대화 폴더의 파일을 「내 파일」 에 넣고, 넣은 파일을 답 아래 줄로 읽는다(ADR-20261119 / file-space-put).
 *
 * <p>대화 폴더가 화면에 내주는 HTML, CSS, 사진은 부르는 파일까지 묶음으로 넣는다. 그 밖의 형식(엑셀, PDF 등)은 파일 하나를 한 번
 * 읽은 바이트로 넣는다. 보관과 주인 규칙은 {@link ArtifactVaultService} 가 갖는다.
 */
@Service
@RequiredArgsConstructor
public class ArtifactPutFiles {

    private final ArtifactStore store;
    private final ArtifactVaultService vault;
    private final ArtifactVaultProperties properties;
    private final ChatArtifactRepository rows;
    private final ArtifactVaultMeta metas;


    /**
     * 에이전트가 준 경로가 이 대화의 결과물 폴더를 가리키면 폴더 안의 상대 경로를 준다.
     *
     * <p>에이전트가 보는 폴더 경로({@link ArtifactStore#agentFolder})로 시작하면 그 뒤를, 상대 경로면 그대로 준다. 그 밖의 절대 경로는
     * 빈 값이다. 다른 대화의 폴더 경로도 여기에 든다.
     */
    public Optional<String> folderPathOf(Long conversationId, String path) {
        String prefix = store.agentFolder(conversationId) + "/";
        if (path.startsWith(prefix)) {
            return Optional.of(path.substring(prefix.length()));
        }
        return path.startsWith("/") ? Optional.empty() : Optional.of(path);
    }


    /**
     * 대화 폴더 안의 파일 하나를 요청자의 「내 파일」 에 넣는다.
     *
     * @param relativePath 대화 폴더 안의 상대 경로
     * @throws ApiException 없거나 폴더 밖이면 {@link ErrorCode#ARTIFACT_NOT_FOUND}. 나머지 거절은 {@link ArtifactVaultService#keep} 과 같다
     */
    public ArtifactKept putFromFolder(
        Long ownerUserId, Long conversationId, String relativePath, String title, String description, Duration wait) {
        String normalized = normalizedOrMissing(relativePath);
        if (ArtifactStore.contentTypeOf(normalized).isPresent()) {
            return vault.keepBundle(ownerUserId, conversationId, normalized, title, description, wait);
        }
        if (VaultFileType.of(normalized).isEmpty()) {
            throw new ApiException(ErrorCode.ARTIFACT_VAULT_UNSUPPORTED, "this kind of file is not kept");
        }
        Path file = store.resolveAnyInside(conversationId, normalized).orElseThrow(ArtifactPutFiles::missing);
        byte[] bytes = readLimited(file, properties.maxFileBytes());
        return vault.keep(ownerUserId, conversationId, VaultOrigin.PUT, normalized, bytes, title, description, wait);
    }


    /**
     * 실행 공간에서 한 번 읽은 바이트를 요청자의 「내 파일」 에 넣는다. 거절은 {@link ArtifactVaultService#keep} 과 같다.
     *
     * @param relativePath 실행 공간 안의 상대 경로
     */
    public ArtifactKept putFromWorkspace(
        Long ownerUserId, Long conversationId, String relativePath, byte[] bytes, String title, String description,
        Duration wait) {
        return vault.keep(
            ownerUserId, conversationId, VaultOrigin.PUT_WORKSPACE, relativePath, bytes, title, description, wait);
    }


    /** 파일 하나의 상한이다. 실행 공간에서 읽을 때 이보다 큰 파일은 읽지 않는다. */
    public long maxFileBytes() {
        return properties.maxFileBytes();
    }


    /**
     * 답 메시지마다 넣기 도구가 넣은 보관본이다. 지운 것도 담아 답 아래에 지운 파일이라고 보이게 한다. 본문은 읽지 않는다.
     */
    public Map<Long, List<PutFileDetail>> byMessage(Collection<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Map.of();
        }
        return rows.findPutFiles(messageIds, VaultOrigin.PUT_ORIGINS).stream()
            .collect(Collectors.groupingBy(
                ArtifactPutFileRow::getMessageId,
                Collectors.mapping(this::detailOf, Collectors.toList())));
    }


    private PutFileDetail detailOf(ArtifactPutFileRow row) {
        String title = metas
            .open(row.getOwnerUserId(), row.getConversationId(), row.getPath(), row.getVaultMeta(), row.getVaultMetaKeyId())
            .map(VaultLabel::title)
            .orElse(null);
        return new PutFileDetail(
            row.getId(), row.getPath(), row.getByteSize() + row.getPartBytes(), row.getDeletedAt() != null, title);
    }


    /** 상한을 넘으면 끝까지 읽지 않고 거절한다. 크기는 열기 전과 읽은 뒤에 모두 본다. */
    private static byte[] readLimited(Path file, long maxBytes) {
        try {
            if (Files.size(file) > maxBytes) {
                throw tooLarge();
            }
            try (InputStream in = Files.newInputStream(file)) {
                byte[] bytes = in.readNBytes(Math.toIntExact(maxBytes + 1));
                if (bytes.length > maxBytes) {
                    throw tooLarge();
                }
                return bytes;
            }
        } catch (NoSuchFileException ex) {
            throw missing();
        } catch (IOException ex) {
            // 읽을 수 없는 파일은 없는 파일과 같게 다룬다. 경로는 남기지 않는다.
            throw missing();
        }
    }


    private static String normalizedOrMissing(String path) {
        String normalized;
        try {
            normalized = ArtifactReferences.normalize(path);
        } catch (ApiException ex) {
            throw missing();
        }
        if (normalized == null || normalized.isEmpty()) {
            throw missing();
        }
        return normalized;
    }


    private static ApiException tooLarge() {
        return new ApiException(ErrorCode.ARTIFACT_VAULT_UNSUPPORTED, "this file is larger than the vault limit");
    }


    private static ApiException missing() {
        return new ApiException(ErrorCode.ARTIFACT_NOT_FOUND, "this file does not exist");
    }
}
