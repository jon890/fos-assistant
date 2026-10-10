package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.MediaObservation;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 행, 대화, 주인을 AAD에 묶는다. 새 본문은 평문으로 대체하지 않는다. */
@Component
@RequiredArgsConstructor
public class MediaObservationBodies {
    private final TextCipher cipher;

    public void requireEncryption() {
        if (!cipher.enabled()) {
            throw unavailable();
        }
    }

    public void seal(MediaObservation row, String plain) {
        requireEncryption();
        try {
            var sealed =
                    cipher.seal(row.ownerUserId(), aad(row), plain).orElseThrow(MediaObservationBodies::unavailable);
            if (sealed.keyId() == null
                    || sealed.content() == null
                    || sealed.content().isBlank()) {
                throw unavailable();
            }
            row.seal(sealed.content(), sealed.keyId());
        } catch (IllegalStateException ex) {
            throw unavailable();
        }
    }

    public Optional<String> open(MediaObservation row, Long owner) {
        if (row.body() == null || !owner.equals(row.ownerUserId())) {
            return Optional.empty();
        }
        if (row.bodyKeyId() == null) {
            return Optional.of(row.body());
        }
        try {
            return cipher.open(row.bodyKeyId(), owner, aad(row), row.body());
        } catch (IllegalStateException ex) {
            return Optional.empty();
        }
    }

    private static String aad(MediaObservation row) {
        return "media_observation:" + row.id() + ":conversation:" + row.conversationId() + ":user:" + row.ownerUserId();
    }

    private static ApiException unavailable() {
        return new ApiException(ErrorCode.MEDIA_ENCRYPTION_UNAVAILABLE, "media encryption is unavailable");
    }
}
