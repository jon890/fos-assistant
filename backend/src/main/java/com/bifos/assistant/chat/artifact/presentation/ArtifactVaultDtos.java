package com.bifos.assistant.chat.artifact.presentation;

import com.bifos.assistant.chat.artifact.application.dto.ArtifactAutoVaultStatus;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactVaultEntry;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactVaultItem;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactVaultPage;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactVaultQuery;
import com.bifos.assistant.chat.artifact.domain.VaultFileType;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
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
     * @param title 에이전트가 붙인 제목. 없으면 null
     * @param description 한 줄 설명. 없으면 null
     * @param kind {@code IMAGE}, {@code DOCUMENT}, {@code TABLE} 가운데 하나
     * @param conversationId 파일이 나온 대화의 공개 식별자. 대화를 지웠거나 보관 직후의 응답이면 null
     * @param conversationTitle 그 대화의 제목. 대화를 지웠거나 보관 직후의 응답이면 null
     */
    public record VaultItemView(
        Long id, String path, long byteSize, int fileCount, Instant vaultedAt, String title, String description,
        String kind, UUID conversationId, String conversationTitle) {

        static VaultItemView of(ArtifactVaultItem item) {
            return new VaultItemView(
                item.id(), item.path(), item.byteSize(), item.fileCount(), item.vaultedAt(), item.title(),
                item.description(), item.kind(), null, null);
        }


        static VaultItemView of(ArtifactVaultEntry entry) {
            ArtifactVaultItem item = entry.item();
            return new VaultItemView(
                item.id(), item.path(), item.byteSize(), item.fileCount(), item.vaultedAt(), item.title(),
                item.description(), item.kind(), entry.conversationId(), entry.conversationTitle());
        }
    }

    /**
     * 「내 파일」 목록 한 쪽이다.
     *
     * @param nextCursor 다음 쪽을 읽을 때 {@code before} 로 줄 값. 없으면 null
     * @param usedBytes 쓴 용량
     * @param quotaBytes 사용자 용량
     * @param full 남은 용량이 파일 하나 상한보다 작다
     */
    public record VaultListView(
        List<VaultItemView> items, Long nextCursor, long usedBytes, long quotaBytes, boolean full) {


        private static final int DEFAULT_LIMIT = 30;
        private static final int MAX_LIMIT = 100;
        private static final int MAX_QUERY_CHARS = 100;

        static VaultListView of(ArtifactVaultPage page) {
            return new VaultListView(
                page.items().stream().map(VaultItemView::of).toList(), page.nextCursor(), page.usedBytes(),
                page.quotaBytes(), page.full());
        }


        /** 요청 인자를 조건으로 바꾼다. 모르는 종류와 범위를 넘는 줄 수는 입력 오류다. */
        static ArtifactVaultQuery query(String kind, String q, Long conversationId, Long before, Integer limit) {
            VaultFileType.Kind parsedKind = null;
            if (kind != null && !kind.isBlank()) {
                try {
                    parsedKind = VaultFileType.Kind.valueOf(kind);
                } catch (IllegalArgumentException ex) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "kind must be IMAGE, DOCUMENT or TABLE");
                }
            }
            String text = q == null ? "" : q.strip();
            if (text.codePointCount(0, text.length()) > MAX_QUERY_CHARS) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "q must have at most 100 characters");
            }
            int size = limit == null ? DEFAULT_LIMIT : limit;
            if (size < 1 || size > MAX_LIMIT) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "limit must be between 1 and 100");
            }
            return new ArtifactVaultQuery(parsedKind, text.isEmpty() ? null : text, conversationId, before, size);
        }
    }

    /**
     * 「다시 보관」 할 결과물이다. 답 메시지 번호와 대화 폴더 안의 상대 경로로 가리킨다.
     *
     * @param conversationId 대화의 공개 식별자
     */
    public record AutoVaultRetryRequest(@NotNull UUID conversationId, @NotNull Long messageId, @NotBlank String path) {
    }

    /**
     * 결과물 하나의 자동 보관 상태다.
     *
     * @param state {@code PENDING}, {@code RUNNING}, {@code DONE}, {@code SKIPPED}, {@code FAILED} 가운데 하나. 대상이 아니면 null
     * @param error {@code FAILED} 일 때 그 까닭의 오류 코드
     */
    public record AutoVaultStatusView(String state, String error) {

        static AutoVaultStatusView of(ArtifactAutoVaultStatus status) {
            return new AutoVaultStatusView(status.state() == null ? null : status.state().name(), status.error());
        }
    }
}
