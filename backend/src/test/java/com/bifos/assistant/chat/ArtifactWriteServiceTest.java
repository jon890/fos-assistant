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
import com.bifos.assistant.chat.infra.ArtifactProperties;
import com.bifos.assistant.chat.infra.ArtifactSourceResponse;
import com.bifos.assistant.chat.infra.ArtifactSourceFetcher;
import com.bifos.assistant.chat.infra.ArtifactSourceProperties;
import com.bifos.assistant.chat.infra.ArtifactStore;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/** 결과물 본문 요청이 대화 주인 확인 뒤에만 저장소에 닿는지 확인한다. */
class ArtifactWriteServiceTest {

    private static final CurrentUser USER = new CurrentUser(1L, "dad@example.com", "아빠", 1L, UserRole.ADMIN);

    @Test
    @DisplayName("본인 대화의 HTML 본문을 UTF 8 크기로 저장한다")
    void savesOwnHtmlBodyWithUtf8Size() {
        ConversationAccess access = mock(ConversationAccess.class);
        ArtifactStore store = mock(ArtifactStore.class);
        Conversation conversation = mock(Conversation.class);
        UUID publicId = UUID.randomUUID();
        when(access.requireOwn(USER, publicId)).thenReturn(conversation);
        when(conversation.id()).thenReturn(42L);
        when(store.write(42L, "test/index.html", "한글".getBytes(StandardCharsets.UTF_8)))
                .thenReturn(6L);

        ArtifactWriteResult result = new ArtifactWriteService(access, store, fetcher())
                .write(USER, new ArtifactWriteRequest(publicId, "test/index.html", "한글", null));

        assertThat(result).isEqualTo(new ArtifactWriteResult("test/index.html", 6L));
    }

    @Test
    @DisplayName("빈 본문과 정확히 5MiB 본문은 받고 한글로 초과하면 저장하지 않는다")
    void acceptsEmptyAndExactly5MiBBodyButRejectsKoreanOverflow() {
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

        assertThat(service.write(USER, new ArtifactWriteRequest(publicId, "a.css", "", null))
                        .byteSize())
                .isZero();
        assertThat(service.write(
                                USER,
                                new ArtifactWriteRequest(
                                        publicId, "b.css", new String(exactLimit, StandardCharsets.ISO_8859_1), null))
                        .byteSize())
                .isEqualTo(exactLimit.length);
        assertThatThrownBy(() -> service.write(
                        USER, new ArtifactWriteRequest(publicId, "c.css", "가".repeat((5 * 1024 * 1024 / 3) + 1), null)))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("없는 대화는 저장소를 부르지 않는다")
    void doesNotCallStoreForMissingConversation() {
        ConversationAccess access = mock(ConversationAccess.class);
        ArtifactStore store = mock(ArtifactStore.class);
        UUID publicId = UUID.randomUUID();
        when(access.requireOwn(USER, publicId))
                .thenThrow(new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "missing"));

        assertThatThrownBy(() -> new ArtifactWriteService(access, store, fetcher())
                        .write(
                                USER,
                                new ArtifactWriteRequest(publicId, "a.png", null, "https://images.example.com/a.png")))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND));

        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("URL 방식과 HTML CSS 밖의 본문은 저장하지 않는다")
    void rejectsUrlMethodAndBodiesOutsideHtmlCss() {
        ConversationAccess access = mock(ConversationAccess.class);
        ArtifactStore store = mock(ArtifactStore.class);
        Conversation conversation = mock(Conversation.class);
        UUID publicId = UUID.randomUUID();
        when(access.requireOwn(USER, publicId)).thenReturn(conversation);
        ArtifactWriteService service = new ArtifactWriteService(access, store, fetcher());

        assertThatThrownBy(() -> service.write(
                        USER, new ArtifactWriteRequest(publicId, "a.html", "본문", "https://example.com/a.html")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.write(USER, new ArtifactWriteRequest(publicId, "a.svg", "본문", null)))
                .isInstanceOf(ApiException.class);

        verify(access, Mockito.times(2)).requireOwn(USER, publicId);
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("본인 대화의 이미지 URL은 내려받은 바이트를 같은 저장소에 쓴다")
    void writesDownloadedBytesOfOwnImageUrlToSameStore() throws Exception {
        ConversationAccess access = mock(ConversationAccess.class);
        ArtifactStore store = mock(ArtifactStore.class);
        Conversation conversation = mock(Conversation.class);
        UUID publicId = UUID.randomUUID();
        when(access.requireOwn(USER, publicId)).thenReturn(conversation);
        when(conversation.id()).thenReturn(42L);
        byte[] image = {1, 2, 3};
        when(store.write(42L, "a.png", image)).thenReturn(3L);
        ArtifactSourceFetcher source = new ArtifactSourceFetcher(
                new ArtifactSourceProperties(List.of("images.example.com"), null, null, null),
                host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")},
                (address, host, uri, connect, read, cancellation) -> new ArtifactSourceResponse(
                        200,
                        Map.of("content-type", "image/png", "content-length", "3"),
                        new ByteArrayInputStream(image)));

        assertThat(new ArtifactWriteService(access, store, source)
                        .write(
                                USER,
                                new ArtifactWriteRequest(publicId, "a.png", null, "https://images.example.com/a.png")))
                .isEqualTo(new ArtifactWriteResult("a.png", 3L));
    }

    @Test
    @DisplayName("URL 경로가 대화 폴더를 벗어나면 DNS 전에 거절한다")
    void rejectsUrlPathEscapingConversationFolderBeforeDns() {
        ConversationAccess access = mock(ConversationAccess.class);
        ArtifactStore store = mock(ArtifactStore.class);
        Conversation conversation = mock(Conversation.class);
        UUID publicId = UUID.randomUUID();
        when(access.requireOwn(USER, publicId)).thenReturn(conversation);
        ArtifactSourceFetcher source = new ArtifactSourceFetcher(
                new ArtifactSourceProperties(List.of("images.example.com"), null, null, null),
                host -> {
                    throw new AssertionError("DNS must not run");
                },
                (address, host, uri, connect, read, cancellation) -> {
                    throw new AssertionError("transport must not run");
                });

        assertThatThrownBy(() -> new ArtifactWriteService(access, store, source)
                        .write(
                                USER,
                                new ArtifactWriteRequest(
                                        publicId, "../a.png", null, "https://images.example.com/a.png")))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("접근할 수 없는 대화의 URL은 DNS 조회와 파일 쓰기를 하지 않는다")
    void skipsDnsAndFileWriteForUrlOfInaccessibleConversation() {
        ConversationAccess access = mock(ConversationAccess.class);
        ArtifactStore store = mock(ArtifactStore.class);
        AtomicInteger dnsCalls = new AtomicInteger();
        ArtifactSourceFetcher source = new ArtifactSourceFetcher(
                new ArtifactSourceProperties(List.of("images.example.com"), null, null, null),
                host -> {
                    dnsCalls.incrementAndGet();
                    throw new AssertionError("DNS must not run");
                },
                (address, host, uri, connect, read, cancellation) -> {
                    throw new AssertionError("transport must not run");
                });
        ArtifactWriteService service = new ArtifactWriteService(access, store, source);

        for (UUID publicId : List.of(UUID.randomUUID(), UUID.randomUUID())) {
            when(access.requireOwn(USER, publicId))
                    .thenThrow(new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "missing"));
            assertThatThrownBy(() -> service.write(
                            USER,
                            new ArtifactWriteRequest(publicId, "a.png", null, "https://images.example.com/a.png")))
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND));
        }

        assertThat(dnsCalls).hasValue(0);
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("URL 다운로드가 실패하면 기존 파일을 보존한다")
    void keepsExistingFileWhenUrlDownloadFails(@TempDir Path root) throws Exception {
        ConversationAccess access = mock(ConversationAccess.class);
        Conversation conversation = mock(Conversation.class);
        UUID publicId = UUID.randomUUID();
        when(access.requireOwn(USER, publicId)).thenReturn(conversation);
        when(conversation.id()).thenReturn(42L);
        ArtifactStore store = new ArtifactStore(new ArtifactProperties(root.toString(), "/agent/artifacts", 30));
        store.write(42L, "a.png", new byte[] {1, 2, 3});
        ArtifactSourceFetcher source = new ArtifactSourceFetcher(
                new ArtifactSourceProperties(List.of("images.example.com"), null, null, null),
                host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")},
                (address, host, uri, connect, read, cancellation) -> {
                    throw new IOException("download failed");
                });

        assertThatThrownBy(() -> new ArtifactWriteService(access, store, source)
                        .write(
                                USER,
                                new ArtifactWriteRequest(publicId, "a.png", null, "https://images.example.com/a.png")))
                .isInstanceOf(ApiException.class);
        assertThat(Files.readAllBytes(root.resolve("42/a.png"))).containsExactly(1, 2, 3);
        try (var files = Files.list(root.resolve("42"))) {
            assertThat(files.map(path -> path.getFileName().toString())).containsExactly("a.png");
        }
    }

    private static ArtifactSourceFetcher fetcher() {
        return new ArtifactSourceFetcher(
                new ArtifactSourceProperties(List.of(), null, null, null),
                host -> new InetAddress[0],
                (address, host, source, connectTimeout, readTimeout, cancellation) -> {
                    throw new IOException("unused");
                });
    }
}
