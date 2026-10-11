package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.shared.util.Sha256;
import java.io.IOException;
import java.util.HexFormat;

/** 원본 전체의 크기를 확인하고 관찰과 원고가 같은 SHA-256 계산을 쓴다. */
public final class AttachmentFingerprint {
    private AttachmentFingerprint() {}

    public static String read(AttachmentStore store, ChatAttachment row) throws IOException {
        var digest = Sha256.newDigest();
        try (var input = store.open(row)) {
            byte[] buffer = new byte[8192];
            long size = 0;
            for (int read; (read = input.read(buffer)) != -1;) {
                size += read;
                if (size > row.byteSize()) {
                    return null;
                }
                digest.update(buffer, 0, read);
            }
            return size == 0 || size != row.byteSize() ? null : HexFormat.of().formatHex(digest.digest());
        }
    }
}
