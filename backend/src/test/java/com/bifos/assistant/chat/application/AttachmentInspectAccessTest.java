package com.bifos.assistant.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.infra.AttachmentStore;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AttachmentInspectAccessTest {
    private final Instant now = Instant.parse("2026-10-10T00:00:00Z");
    private final CurrentUser user = new CurrentUser(1L, "owner@example.test", "사용자", 1L, UserRole.MEMBER);
    private final ConversationAccess access = mock(ConversationAccess.class);
    private final ChatAttachmentRepository attachments = mock(ChatAttachmentRepository.class);
    private final AttachmentStore store = mock(AttachmentStore.class);
    private final AttachmentInspection inspection = mock(AttachmentInspection.class);
    private final ChatAttachment photo = mock(ChatAttachment.class);
    private final AttachmentService service =
            new AttachmentService(access, attachments, store, null, Clock.fixed(now, ZoneOffset.UTC), null, inspection);

    @BeforeEach
    void setUp() {
        when(attachments.findByIdAndConversationId(7L, 2L)).thenReturn(Optional.of(photo));
        when(photo.uploadedByUserId()).thenReturn(1L);
        when(photo.messageId()).thenReturn(3L);
        when(photo.isVisible()).thenReturn(true);
        when(photo.expiresAt()).thenReturn(now.plusSeconds(1));
        when(attachments
                        .existsByIdAndConversationIdAndUploadedByUserIdAndMessageIdIsNotNullAndDeletedAtIsNullAndExpiresAtAfter(
                                7L, 2L, 1L, now))
                .thenReturn(true);
    }

    @Test
    @DisplayName("GIF 원본의 20MiB 상한과 저장 크기 및 MIME magic을 확인하고 Java decode는 하지 않는다")
    void boundsRawBytesAndRejectsStoredSizeMismatchOrWrongMagic() {
        when(photo.contentType()).thenReturn("image/gif");
        byte[] bytes = new byte[20 * 1024 * 1024];
        System.arraycopy(new byte[] {'G', 'I', 'F', '8', '9', 'a'}, 0, bytes, 0, 6);
        when(photo.byteSize()).thenReturn((long) bytes.length);
        when(store.open(photo)).thenAnswer(invocation -> new ByteArrayInputStream(bytes));
        assertThat(service.inspect(user, 2L, 7L, null).bytes()).containsExactly(bytes);
        when(photo.byteSize()).thenReturn((long) bytes.length + 1);
        assertThatThrownBy(() -> service.inspect(user, 2L, 7L, null)).isInstanceOf(ApiException.class);
        byte[] overflow = java.util.Arrays.copyOf(bytes, bytes.length + 1);
        when(store.open(photo)).thenReturn(new ByteArrayInputStream(overflow));
        assertThatThrownBy(() -> service.inspect(user, 2L, 7L, null)).isInstanceOf(ApiException.class);
        when(photo.contentType()).thenReturn("image/webp");
        when(store.open(photo)).thenReturn(new ByteArrayInputStream(bytes));
        assertThatThrownBy(() -> service.inspect(user, 2L, 7L, null)).isInstanceOf(ApiException.class);
        verifyNoInteractions(inspection);
    }

    @Test
    @DisplayName("캐시의 첨부가 유효해도 SQL 상태가 바뀌면 재검증을 거절한다")
    void rejectsSqlRevocationEvenWhenCachedEntityIsVisible() {
        service.validateInspection(user, 2L, 7L);
        when(attachments
                        .existsByIdAndConversationIdAndUploadedByUserIdAndMessageIdIsNotNullAndDeletedAtIsNullAndExpiresAtAfter(
                                7L, 2L, 1L, now))
                .thenReturn(false);
        assertThatThrownBy(() -> service.validateInspection(user, 2L, 7L)).isInstanceOf(ApiException.class);
        verifyNoInteractions(inspection, store);
    }

    @Test
    @DisplayName("다른 대화와 업로더 및 미전송 삭제 만료 사진을 거절한다")
    void rejectsOtherConversationUploaderUnsentDeletedAndExpiredPhoto() {
        assertThatThrownBy(() -> service.inspect(user, 8L, 7L, null)).isInstanceOf(ApiException.class);
        when(photo.uploadedByUserId()).thenReturn(9L);
        assertThatThrownBy(() -> service.inspect(user, 2L, 7L, null)).isInstanceOf(ApiException.class);
        when(photo.uploadedByUserId()).thenReturn(1L);
        when(photo.messageId()).thenReturn(null);
        assertThatThrownBy(() -> service.inspect(user, 2L, 7L, null)).isInstanceOf(ApiException.class);
        when(photo.messageId()).thenReturn(3L);
        when(photo.isVisible()).thenReturn(false);
        assertThatThrownBy(() -> service.inspect(user, 2L, 7L, null)).isInstanceOf(ApiException.class);
        when(photo.isVisible()).thenReturn(true);
        when(photo.expiresAt()).thenReturn(now);
        assertThatThrownBy(() -> service.inspect(user, 2L, 7L, null)).isInstanceOf(ApiException.class);
        verifyNoInteractions(inspection);
    }

    @Test
    @DisplayName("보낸 지난 사진을 허용하되 읽는 중 삭제되면 반환하지 않는다")
    void allowsSentPastPhotoButDeletionDuringReadDoesNotReturnPixels() {
        InspectedImage result = new InspectedImage("image/png", new byte[] {1});
        when(inspection.read(eq(photo), eq(null), any())).thenReturn(result);
        assertThat(service.inspect(user, 2L, 7L, null)).isEqualTo(result);
        when(inspection.read(eq(photo), eq(null), any())).thenAnswer(invocation -> {
            when(photo.isVisible()).thenReturn(false);
            return result;
        });
        assertThatThrownBy(() -> service.inspect(user, 2L, 7L, null)).isInstanceOf(ApiException.class);
    }
}
