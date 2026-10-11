package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.application.ContentDraftException;
import com.bifos.assistant.chat.domain.entity.ContentDraft;
import com.bifos.assistant.chat.domain.entity.ContentDraftRevision;
import com.bifos.assistant.crypto.domain.SealedText;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.shared.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 공개 UUID와 수락 번호로 AAD를 정하여 NULL VALID 행을 먼저 저장하지 않는다. */
@Component
@RequiredArgsConstructor
public class ContentDraftBodies {
    private final TextCipher cipher;

    public SealedText seal(ContentDraft draft, long revision, String plain) {
        try {
            if (!cipher.enabled()) {
                throw unavailable();
            }
            var sealed = cipher.seal(draft.ownerUserId(), aad(draft, revision), plain).orElseThrow(ContentDraftBodies::unavailable);
            if (sealed.keyId() == null || sealed.content() == null || sealed.content().isBlank()) {
                throw unavailable();
            }
            return sealed;
        } catch (IllegalStateException ex) {
            throw unavailable();
        }
    }

    public String open(ContentDraft draft, ContentDraftRevision row) {
        try {
            if (row.body() == null || row.bodyKeyId() == null) {
                throw unavailable();
            }
            return cipher.open(row.bodyKeyId(), draft.ownerUserId(), aad(draft, row.revision()), row.body())
                .orElseThrow(ContentDraftBodies::unavailable);
        } catch (IllegalStateException ex) {
            throw unavailable();
        }
    }

    private static String aad(ContentDraft draft, long revision) {
        return "content_draft_revision:" + draft.publicId() + ":revision:" + revision
            + ":conversation:" + draft.conversationId() + ":user:" + draft.ownerUserId();
    }

    public static ContentDraftException unavailable() {
        return new ContentDraftException(ErrorCode.CONTENT_DRAFT_UNAVAILABLE, null);
    }
}
