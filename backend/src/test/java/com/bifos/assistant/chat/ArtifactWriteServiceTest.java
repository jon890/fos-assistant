package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bifos.assistant.chat.application.ArtifactWriteRequest;
import com.bifos.assistant.chat.application.ArtifactWriteResult;
import com.bifos.assistant.chat.application.ArtifactWriteService;
import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ArtifactStore;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.UserRole;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 결과물 본문 요청이 대화 주인 확인 뒤에만 저장소에 닿는지 확인한다. */
class ArtifactWriteServiceTest {

    private static final CurrentUser USER = new CurrentUser(1L, "dad@example.com", "아빠", 1L, UserRole.ADMIN);

    @Test
    void 본인_대화의_HTML_본문을_UTF_8_크기로_저장한다() {
        ConversationAccess access = mock(ConversationAccess.class);
        ArtifactStore store = mock(ArtifactStore.class);
        Conversation conversation = mock(Conversation.class);
        UUID publicId = UUID.randomUUID();
        when(access.requireOwn(USER, publicId)).thenReturn(conversation);
        when(conversation.id()).thenReturn(42L);
        when(store.write(42L, "test/index.html", "한글".getBytes(StandardCharsets.UTF_8))).thenReturn(6L);

        ArtifactWriteResult result = new ArtifactWriteService(access, store)
                .write(USER, new ArtifactWriteRequest(publicId, "test/index.html", "한글", null));

        assertThat(result).isEqualTo(new ArtifactWriteResult("test/index.html", 6L));
    }

    @Test
    void 빈_본문과_정확히_5MiB_본문은_받고_한글로_초과하면_저장하지_않는다() {
        ConversationAccess access = mock(ConversationAccess.class);
        ArtifactStore store = mock(ArtifactStore.class);
        Conversation conversation = mock(Conversation.class);
        UUID publicId = UUID.randomUUID();
        when(access.requireOwn(USER, publicId)).thenReturn(conversation);
        when(conversation.id()).thenReturn(42L);
        when(store.write(42L, "a.css", new byte[0])).thenReturn(0L);
        byte[] exactLimit = new byte[5 * 1024 * 1024];
        when(store.write(42L, "b.css", exactLimit)).thenReturn((long) exactLimit.length);
        ArtifactWriteService service = new ArtifactWriteService(access, store);

        assertThat(service.write(USER, new ArtifactWriteRequest(publicId, "a.css", "", null)).byteSize()).isZero();
        assertThat(service.write(USER, new ArtifactWriteRequest(publicId, "b.css",
                new String(exactLimit, StandardCharsets.ISO_8859_1), null)).byteSize()).isEqualTo(exactLimit.length);
        assertThatThrownBy(() -> service.write(USER, new ArtifactWriteRequest(publicId, "c.css",
                "가".repeat((5 * 1024 * 1024 / 3) + 1), null)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void 없는_대화는_저장소를_부르지_않는다() {
        ConversationAccess access = mock(ConversationAccess.class);
        ArtifactStore store = mock(ArtifactStore.class);
        UUID publicId = UUID.randomUUID();
        when(access.requireOwn(USER, publicId))
                .thenThrow(new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "missing"));

        assertThatThrownBy(() -> new ArtifactWriteService(access, store)
                .write(USER, new ArtifactWriteRequest(publicId, "a.html", "본문", null)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND));

        verifyNoInteractions(store);
    }

    @Test
    void URL_방식과_HTML_CSS_밖의_본문은_저장하지_않는다() {
        ConversationAccess access = mock(ConversationAccess.class);
        ArtifactStore store = mock(ArtifactStore.class);
        Conversation conversation = mock(Conversation.class);
        UUID publicId = UUID.randomUUID();
        when(access.requireOwn(USER, publicId)).thenReturn(conversation);
        ArtifactWriteService service = new ArtifactWriteService(access, store);

        assertThatThrownBy(() -> service.write(USER,
                new ArtifactWriteRequest(publicId, "a.html", "본문", "https://example.com/a.html")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.write(USER, new ArtifactWriteRequest(publicId, "a.svg", "본문", null)))
                .isInstanceOf(ApiException.class);

        verify(access, org.mockito.Mockito.times(2)).requireOwn(USER, publicId);
        verifyNoInteractions(store);
    }
}
