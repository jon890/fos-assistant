package com.bifos.assistant.chat.artifact.presentation;

import com.bifos.assistant.chat.artifact.application.dto.ArtifactVaultItem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 보관한 결과물 경로의 요청과 응답 모양이다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ArtifactVaultDtos {

    /** 보관할 결과물이다. 대화는 공개 식별자로, 파일은 대화 폴더 안의 상대 경로로 가리킨다. */
    public record VaultRequest(@NotNull UUID conversationId, @NotBlank String path) {
    }

    /**
     * 보관본 하나다.
     *
     * @param byteSize 묶음 전체의 평문 바이트 수
     * @param fileCount 묶음의 파일 수. 뿌리를 센다
     */
    public record VaultItemView(Long id, String path, long byteSize, int fileCount, Instant vaultedAt) {

        static VaultItemView of(ArtifactVaultItem item) {
            return new VaultItemView(item.id(), item.path(), item.byteSize(), item.fileCount(), item.vaultedAt());
        }
    }

    public record VaultListView(List<VaultItemView> items) {
    }
}
