package com.bifos.assistant.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
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
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AttachmentInspectAccessTest {
    private final Instant now = Instant.parse("2026-10-10T00:00:00Z");
    private final CurrentUser user = new CurrentUser(1L, "owner@example.test", "사용자", 1L, UserRole.MEMBER);
    private final ConversationAccess access = mock(ConversationAccess.class);
    private final ChatAttachmentRepository attachments = mock(ChatAttachmentRepository.class);
    private final AttachmentStore store = mock(AttachmentStore.class);
    private final AttachmentInspection inspection = mock(AttachmentInspection.class);
    private final ChatAttachment photo = mock(ChatAttachment.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final AttachmentService service = new AttachmentService(
            access,
            attachments,
            store,
            null,
            Clock.fixed(now, ZoneOffset.UTC),
            null,
            inspection,
            mock(AttachmentCleaner.class),
            transactions);

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(photo.id()).thenReturn(7L);
        when(photo.conversationId()).thenReturn(2L);
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
        byte[] overflow = Arrays.copyOf(bytes, bytes.length + 1);
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("열기 뒤 SQL 검사에서 접근이 차단되면 스트림을 닫고 닫기 실패도 원래 거절을 가리지 않는다")
    void closesOriginalWhenPostOpenCheckRejects(boolean failClose) {
        var closed = new AtomicBoolean();
        var closeFailure = new IOException("synthetic close failure");
        when(store.open(photo)).thenReturn(trackedStream(closed, failClose ? closeFailure : null));
        when(attachments.existsReadable(7L, 2L, 1L)).thenReturn(true, false);

        assertThatThrownBy(() -> service.read(user, 2L, 7L)).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.code()).isEqualTo(ErrorCode.ATTACHMENT_GONE);
            assertThat(ex.getSuppressed())
                    .containsExactly(failClose ? new Throwable[] {closeFailure} : new Throwable[0]);
        });
        assertThat(closed).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("열기 뒤 SQLException이 발생하면 스트림을 닫고 닫기 IOException은 원래 SQL 예외에 붙인다")
    void closesOriginalWhenPostOpenSqlFails(boolean failClose) {
        var closed = new AtomicBoolean();
        var closeFailure = new IOException("synthetic close failure");
        var sqlFailure = new DataAccessResourceFailureException("synthetic query failure", new SQLException("offline"));
        when(store.open(photo)).thenReturn(trackedStream(closed, failClose ? closeFailure : null));
        when(attachments.existsReadable(7L, 2L, 1L)).thenReturn(true).thenThrow(sqlFailure);

        assertThatThrownBy(() -> service.read(user, 2L, 7L)).isSameAs(sqlFailure);
        assertThat(closed).isTrue();
        assertThat(sqlFailure.getSuppressed())
                .containsExactly(failClose ? new Throwable[] {closeFailure} : new Throwable[0]);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("열기 뒤 트랜잭션 시작 또는 커밋이 실패해도 스트림을 닫고 원래 예외를 전파한다")
    void closesOriginalWhenPostOpenTransactionFails(boolean failCommit) {
        var closed = new AtomicBoolean();
        var failure = new CannotCreateTransactionException("synthetic transaction failure");
        when(attachments.existsReadable(7L, 2L, 1L)).thenReturn(true);
        when(store.open(photo)).thenAnswer(invocation -> {
            if (failCommit) {
                doThrow(failure).when(transactions).commit(any());
            } else {
                when(transactions.getTransaction(any())).thenThrow(failure);
            }
            return trackedStream(closed, null);
        });

        assertThatThrownBy(() -> service.read(user, 2L, 7L)).isSameAs(failure);
        assertThat(closed).isTrue();
    }

    private static InputStream trackedStream(AtomicBoolean closed, IOException failure) {
        return new ByteArrayInputStream(new byte[] {1}) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
                if (failure != null) {
                    throw failure;
                }
            }
        };
    }
}
