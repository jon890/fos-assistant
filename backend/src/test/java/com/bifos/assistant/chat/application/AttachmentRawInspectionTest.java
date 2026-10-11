package com.bifos.assistant.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.infra.AttachmentStore;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AttachmentRawInspectionTest {
    private final Instant now = Instant.parse("2026-10-10T00:00:00Z");
    private final CurrentUser user = new CurrentUser(1L, "owner@example.test", "사용자", 1L, UserRole.MEMBER);
    private final ChatAttachmentRepository attachments = mock(ChatAttachmentRepository.class);
    private final AttachmentStore store = mock(AttachmentStore.class);
    private final ChatAttachment photo = mock(ChatAttachment.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final AttachmentService service = new AttachmentService(
            mock(ConversationAccess.class), attachments, store, null, Clock.fixed(now, ZoneOffset.UTC), null,
            new AttachmentInspection(store), mock(AttachmentCleaner.class), transactions);

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(attachments.findByIdAndConversationId(7L, 2L)).thenReturn(Optional.of(photo));
        when(photo.id()).thenReturn(7L);
        when(photo.conversationId()).thenReturn(2L);
        when(photo.uploadedByUserId()).thenReturn(1L);
        when(photo.messageId()).thenReturn(3L);
        when(photo.isVisible()).thenReturn(true);
        when(photo.expiresAt()).thenReturn(now.plusSeconds(1));
        allowInspection(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/jpeg", "image/png", "image/gif", "image/webp"})
    @DisplayName("개요 조회는 네 형식의 원본 bytes를 디코딩 없이 그대로 반환하고 스트림을 닫는다")
    void overviewPreservesEachOriginalFormat(String mime) {
        byte[] bytes = switch (mime) {
            case "image/jpeg" -> new byte[] {(byte) 0xff, (byte) 0xd8, 1};
            case "image/png" -> new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1};
            case "image/gif" -> new byte[] {'G', 'I', 'F', '8', '7', 'a', 1};
            default -> new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 1};
        };
        var closed = new AtomicBoolean();
        when(photo.contentType()).thenReturn(mime);
        when(photo.byteSize()).thenReturn((long) bytes.length);
        when(store.open(photo)).thenReturn(new ByteArrayInputStream(bytes) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        });

        InspectedImage result = service.inspectOverview(user, 2L, 7L);

        assertThat(result.contentType()).isEqualTo(mime);
        assertThat(result.bytes()).containsExactly(bytes);
        assertThat(closed).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "magic", "size", "unsupported"})
    @DisplayName("빈 원본과 MIME 및 저장 크기 불일치를 원본 검사 실패로 반환한다")
    void rejectsInvalidOriginal(String invalid) {
        byte[] bytes = invalid.equals("empty") ? new byte[0] : new byte[] {(byte) 0xff, (byte) 0xd8};
        when(photo.contentType()).thenReturn(switch (invalid) {
            case "magic" -> "image/png";
            case "unsupported" -> "application/octet-stream";
            default -> "image/jpeg";
        });
        when(photo.byteSize()).thenReturn(invalid.equals("size") ? 3L : (long) bytes.length);
        when(store.open(photo)).thenReturn(new ByteArrayInputStream(bytes));

        assertThatThrownBy(() -> service.inspectOverview(user, 2L, 7L))
                .isInstanceOfSatisfying(ApiException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(ErrorCode.ATTACHMENT_INSPECTION_FAILED);
                    assertThat(failure.getMessage()).isEqualTo("invalid original image");
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("원본 읽기와 닫기 IOException은 같은 오류 코드와 원인을 보존한다")
    void preservesIoFailure(boolean failClose) {
        var failure = new IOException("synthetic original failure");
        when(photo.contentType()).thenReturn("image/jpeg");
        when(photo.byteSize()).thenReturn(2L);
        when(store.open(photo)).thenReturn(new InputStream() {
            private int offset;

            @Override
            public int read() throws IOException {
                if (!failClose) {
                    throw failure;
                }
                return offset++ < 2 ? (offset == 1 ? 0xff : 0xd8) : -1;
            }

            @Override
            public void close() throws IOException {
                if (failClose) {
                    throw failure;
                }
            }
        });

        assertThatThrownBy(() -> service.inspectOverview(user, 2L, 7L))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.code()).isEqualTo(ErrorCode.ATTACHMENT_INSPECTION_FAILED);
                    assertThat(error.getMessage()).isEqualTo("could not read original image");
                    assertThat(error.getCause()).isSameAs(failure);
                });
    }

    @Test
    @DisplayName("원본을 읽는 중 SQL 권한이 바뀌면 bytes를 반환하지 않는다")
    void rejectsRevocationDuringRawRead() {
        when(photo.contentType()).thenReturn("image/jpeg");
        when(photo.byteSize()).thenReturn(2L);
        when(store.open(photo)).thenAnswer(invocation -> {
            allowInspection(false);
            return new ByteArrayInputStream(new byte[] {(byte) 0xff, (byte) 0xd8});
        });

        assertThatThrownBy(() -> service.inspectOverview(user, 2L, 7L))
                .isInstanceOfSatisfying(ApiException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.ATTACHMENT_GONE));
    }

    private void allowInspection(boolean allowed) {
        when(attachments
                .existsByIdAndConversationIdAndUploadedByUserIdAndMessageIdIsNotNullAndDeletedAtIsNullAndExpiresAtAfter(
                        7L, 2L, 1L, now)).thenReturn(allowed);
    }
}
