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
import com.bifos.assistant.chat.application.ArtifactProperties;
import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ArtifactStore;
import com.bifos.assistant.chat.infra.ArtifactSourceFetcher;
import com.bifos.assistant.chat.application.ArtifactSourceProperties;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.UserRole;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

        ArtifactWriteResult result = new ArtifactWriteService(access, store, fetcher())
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
        ArtifactWriteService service = new ArtifactWriteService(access, store, fetcher());

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

        assertThatThrownBy(() -> new ArtifactWriteService(access, store, fetcher())
                .write(USER, new ArtifactWriteRequest(publicId, "a.png", null, "https://images.example.com/a.png")))
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
        ArtifactWriteService service = new ArtifactWriteService(access, store, fetcher());

        assertThatThrownBy(() -> service.write(USER,
                new ArtifactWriteRequest(publicId, "a.html", "본문", "https://example.com/a.html")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.write(USER, new ArtifactWriteRequest(publicId, "a.svg", "본문", null)))
                .isInstanceOf(ApiException.class);

        verify(access, org.mockito.Mockito.times(2)).requireOwn(USER, publicId);
        verifyNoInteractions(store);
    }

    @Test
    void 본인_대화의_이미지_URL은_내려받은_바이트를_같은_저장소에_쓴다() throws Exception {
        ConversationAccess access = mock(ConversationAccess.class);
        ArtifactStore store = mock(ArtifactStore.class);
        Conversation conversation = mock(Conversation.class);
        UUID publicId = UUID.randomUUID();
        when(access.requireOwn(USER, publicId)).thenReturn(conversation);
        when(conversation.id()).thenReturn(42L);
        byte[] image = {1, 2, 3};
        when(store.write(42L, "a.png", image)).thenReturn(3L);
        ArtifactSourceFetcher source = new ArtifactSourceFetcher(
                new ArtifactSourceProperties(java.util.List.of("images.example.com"), null, null, null),
                host -> new java.net.InetAddress[] {java.net.InetAddress.getByName("8.8.8.8")},
                (address, host, uri, connect, read, cancellation) -> new ArtifactSourceFetcher.Response(200,
                        java.util.Map.of("content-type", "image/png", "content-length", "3"), new java.io.ByteArrayInputStream(image)));

        assertThat(new ArtifactWriteService(access, store, source).write(USER,
                new ArtifactWriteRequest(publicId, "a.png", null, "https://images.example.com/a.png")))
                .isEqualTo(new ArtifactWriteResult("a.png", 3L));
    }

    @Test
    void URL_경로가_대화_폴더를_벗어나면_DNS_전에_거절한다() {
        ConversationAccess access = mock(ConversationAccess.class);
        ArtifactStore store = mock(ArtifactStore.class);
        Conversation conversation = mock(Conversation.class);
        UUID publicId = UUID.randomUUID();
        when(access.requireOwn(USER, publicId)).thenReturn(conversation);
        ArtifactSourceFetcher source = new ArtifactSourceFetcher(
                new ArtifactSourceProperties(java.util.List.of("images.example.com"), null, null, null),
                host -> { throw new AssertionError("DNS must not run"); },
                (address, host, uri, connect, read, cancellation) -> { throw new AssertionError("transport must not run"); });

        assertThatThrownBy(() -> new ArtifactWriteService(access, store, source).write(USER,
                new ArtifactWriteRequest(publicId, "../a.png", null, "https://images.example.com/a.png")))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verifyNoInteractions(store);
    }

    @Test
    void 접근할_수_없는_대화의_URL은_DNS_조회와_파일_쓰기를_하지_않는다() {
        ConversationAccess access = mock(ConversationAccess.class);
        ArtifactStore store = mock(ArtifactStore.class);
        AtomicInteger dnsCalls = new AtomicInteger();
        ArtifactSourceFetcher source = new ArtifactSourceFetcher(
                new ArtifactSourceProperties(java.util.List.of("images.example.com"), null, null, null),
                host -> { dnsCalls.incrementAndGet(); throw new AssertionError("DNS must not run"); },
                (address, host, uri, connect, read, cancellation) -> { throw new AssertionError("transport must not run"); });
        ArtifactWriteService service = new ArtifactWriteService(access, store, source);

        for (UUID publicId : java.util.List.of(UUID.randomUUID(), UUID.randomUUID())) {
            when(access.requireOwn(USER, publicId))
                    .thenThrow(new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "missing"));
            assertThatThrownBy(() -> service.write(USER,
                    new ArtifactWriteRequest(publicId, "a.png", null, "https://images.example.com/a.png")))
                    .isInstanceOfSatisfying(ApiException.class,
                            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND));
        }

        assertThat(dnsCalls).hasValue(0);
        verifyNoInteractions(store);
    }

    @Test
    void URL_다운로드가_실패하면_기존_파일을_보존한다(@TempDir Path root) throws Exception {
        ConversationAccess access = mock(ConversationAccess.class);
        Conversation conversation = mock(Conversation.class);
        UUID publicId = UUID.randomUUID();
        when(access.requireOwn(USER, publicId)).thenReturn(conversation);
        when(conversation.id()).thenReturn(42L);
        ArtifactStore store = new ArtifactStore(new ArtifactProperties(root.toString(), "/agent/artifacts", 30));
        store.write(42L, "a.png", new byte[] {1, 2, 3});
        ArtifactSourceFetcher source = new ArtifactSourceFetcher(
                new ArtifactSourceProperties(java.util.List.of("images.example.com"), null, null, null),
                host -> new java.net.InetAddress[] {java.net.InetAddress.getByName("8.8.8.8")},
                (address, host, uri, connect, read, cancellation) -> { throw new java.io.IOException("download failed"); });

        assertThatThrownBy(() -> new ArtifactWriteService(access, store, source).write(USER,
                new ArtifactWriteRequest(publicId, "a.png", null, "https://images.example.com/a.png")))
                .isInstanceOf(ApiException.class);
        assertThat(Files.readAllBytes(root.resolve("42/a.png"))).containsExactly(1, 2, 3);
        try (var files = Files.list(root.resolve("42"))) {
            assertThat(files.map(path -> path.getFileName().toString())).containsExactly("a.png");
        }
    }

    private static ArtifactSourceFetcher fetcher() {
        return new ArtifactSourceFetcher(new ArtifactSourceProperties(java.util.List.of(), null, null, null), host -> new java.net.InetAddress[0],
                (address, host, source, connectTimeout, readTimeout, cancellation) -> { throw new java.io.IOException("unused"); });
    }
}
