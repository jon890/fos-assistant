package com.bifos.assistant.chat.artifact.application;

import com.bifos.assistant.chat.artifact.application.dto.ArtifactVaultEntry;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactVaultItem;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactVaultPage;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactVaultQuery;
import com.bifos.assistant.chat.artifact.config.ArtifactVaultProperties;
import com.bifos.assistant.chat.artifact.domain.VaultFileType;
import com.bifos.assistant.chat.artifact.domain.VaultLabel;
import com.bifos.assistant.chat.artifact.infra.ArtifactVaultMeta;
import com.bifos.assistant.chat.artifact.infra.ArtifactVaultRow;
import com.bifos.assistant.chat.artifact.infra.ChatArtifactRepository;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「내 파일」 목록을 거르고 찾아 한 쪽씩 준다(스펙 workspace REQ10~REQ13, REQ17).
 *
 * <p>제목, 설명, 대화 제목은 사용자별 key 로 암호화돼 있어 데이터베이스가 찾지 못한다. 그 사용자의 살아 있는 보관 뿌리 행을 본문 없이
 * 모두 읽고 메모리에서 거른다. 종류와 대화는 경로와 대화 번호로 거르고, 낱말이 있을 때만 남은 행의 제목과 대화 제목을 푼다. 쪽은 번호
 * 역순으로 자르므로 쪽 사이에 파일이 들어오거나 지워져도 남은 줄이 빠지거나 겹치지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ArtifactVaultFinder {

    private final ChatArtifactRepository rows;
    private final ArtifactVaultMeta metas;
    private final ConversationRepository conversations;
    private final ArtifactVaultProperties properties;


    /** 조건에 맞는 보관본 한 쪽과 용량이다. 대화 주인 확인은 부르는 쪽이 한다. */
    @Transactional(readOnly = true)
    public ArtifactVaultPage page(Long ownerUserId, ArtifactVaultQuery query) {
        Lookup lookup = new Lookup(ownerUserId);
        List<ArtifactVaultRow> matched = rows.listVaulted(ownerUserId).stream()
            .filter(row -> query.before() == null || row.getId() < query.before())
            .filter(row -> query.kind() == null || isKind(row, query.kind()))
            .filter(row -> query.conversationId() == null || query.conversationId().equals(row.getConversationId()))
            .toList();
        if (query.text() != null) {
            lookup.loadConversations(matched.stream().map(ArtifactVaultRow::getConversationId).toList());
            String needle = query.text().toLowerCase(Locale.ROOT);
            matched = matched.stream().filter(row -> lookup.matches(row, needle)).toList();
        }
        List<ArtifactVaultRow> page = matched.stream().limit(query.limit()).toList();
        lookup.loadConversations(page.stream().map(ArtifactVaultRow::getConversationId).toList());
        Long nextCursor = matched.size() > page.size() ? page.getLast().getId() : null;
        long used = rows.sumVaultBytes(ownerUserId);
        long quota = properties.userQuotaBytes();
        return new ArtifactVaultPage(
            page.stream().map(lookup::entry).toList(), nextCursor, used, quota,
            quota - used < properties.maxFileBytes());
    }


    /** 보관본 하나다. 없거나 남의 것이면 없는 번호와 같은 응답이다. */
    @Transactional(readOnly = true)
    public ArtifactVaultEntry one(Long ownerUserId, Long id) {
        Lookup lookup = new Lookup(ownerUserId);
        ArtifactVaultRow row = rows.listVaulted(ownerUserId).stream()
            .filter(candidate -> candidate.getId().equals(id))
            .findFirst()
            .orElseThrow(() -> new ApiException(ErrorCode.ARTIFACT_NOT_FOUND, "this kept file does not exist"));
        lookup.loadConversations(List.of(row.getConversationId()));
        return lookup.entry(row);
    }


    private static boolean isKind(ArtifactVaultRow row, VaultFileType.Kind kind) {
        return VaultFileType.of(row.getPath()).map(type -> type.kind() == kind).orElse(false);
    }


    /** 남아 있는 내 대화의 공개 식별자와 제목이다. */
    private record ConversationRef(UUID publicId, String title) {
    }

    /** 한 요청 안에서 푼 제목과 대화를 다시 풀지 않게 모아 둔다. */
    private final class Lookup {

        private final Long ownerUserId;
        private final Map<Long, Optional<VaultLabel>> labels = new HashMap<>();
        private final Map<Long, Optional<ConversationRef>> refs = new HashMap<>();


        private Lookup(Long ownerUserId) {
            this.ownerUserId = ownerUserId;
        }


        /** 아직 읽지 않은 대화를 한 번에 읽는다. 지웠거나 남의 대화는 빈 값으로 둔다. */
        void loadConversations(Collection<Long> conversationIds) {
            List<Long> missing = conversationIds.stream().filter(Objects::nonNull).distinct().filter(id -> !refs.containsKey(id)).toList();
            if (missing.isEmpty()) {
                return;
            }
            missing.forEach(id -> refs.put(id, Optional.empty()));
            for (Conversation conversation : conversations.findAllById(missing)) {
                if (conversation.deletedAt() == null && ownerUserId.equals(conversation.userId())) {
                    refs.put(conversation.id(), Optional.of(new ConversationRef(conversation.publicId(), conversation.title())));
                }
            }
        }


        boolean matches(ArtifactVaultRow row, String needle) {
            Optional<VaultLabel> label = label(row);
            return contains(row.getPath(), needle)
                || label.map(VaultLabel::title).filter(title -> contains(title, needle)).isPresent()
                || label.map(VaultLabel::description).filter(text -> contains(text, needle)).isPresent()
                || ref(row).map(ConversationRef::title).filter(title -> contains(title, needle)).isPresent();
        }


        ArtifactVaultEntry entry(ArtifactVaultRow row) {
            Optional<VaultLabel> label = label(row);
            Optional<ConversationRef> ref = ref(row);
            ArtifactVaultItem item = new ArtifactVaultItem(
                row.getId(), row.getPath(), row.getByteSize() + row.getPartBytes(),
                Math.toIntExact(row.getPartCount() + 1), row.getCreatedAt(), label.map(VaultLabel::title).orElse(null),
                label.map(VaultLabel::description).orElse(null));
            return new ArtifactVaultEntry(
                item, ref.map(ConversationRef::publicId).orElse(null), ref.map(ConversationRef::title).orElse(null));
        }


        private Optional<VaultLabel> label(ArtifactVaultRow row) {
            return labels.computeIfAbsent(row.getId(), id -> metas.open(
                ownerUserId, row.getConversationId(), row.getPath(), row.getVaultMeta(), row.getVaultMetaKeyId()));
        }


        private Optional<ConversationRef> ref(ArtifactVaultRow row) {
            return refs.getOrDefault(row.getConversationId(), Optional.empty());
        }


        private static boolean contains(String value, String needle) {
            return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
        }
    }
}
