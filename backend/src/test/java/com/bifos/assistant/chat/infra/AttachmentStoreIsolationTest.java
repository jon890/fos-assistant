package com.bifos.assistant.chat.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

/** 사용자별 첨부 저장의 파일 경계를 실제 파일 시스템에서 확인한다. */
class AttachmentStoreIsolationTest {

    @TempDir
    Path temporary;

    private Path root;
    private AttachmentStore store;

    @BeforeEach
    void setUp() {
        root = temporary.resolve("attachments");
        store = new AttachmentStore(new AttachmentProperties(root.toString(), "/images", null, null, null));
    }

    @Test
    @DisplayName("Hermes와 같은 실행 주인 문자열은 고정한 SHA-256 사용자 디렉터리 키가 된다")
    void matchesHermesOwnerDirectoryKey() {
        assertThat(AttachmentStore.userDirectoryKey(11L))
                .isEqualTo("92ec86fa88925dabc1026bfc9335f5f6848886c98586c005f4a0c02716e3bbab");
    }

    @Test
    @DisplayName("사용자별 저장은 다른 사용자 디렉터리에 파일을 만들지 않는다")
    void storesImagesUnderSeparateUserDirectories() throws IOException {
        ChatAttachment alice = attachment(1L, 11L, 101L);
        ChatAttachment bob = attachment(2L, 22L, 202L);
        store.save(alice, new ByteArrayInputStream(new byte[] {1}));
        store.save(bob, new ByteArrayInputStream(new byte[] {2}));

        assertThat(fileOf(alice)).exists();
        assertThat(fileOf(bob)).exists();
        Path aliceDirectory = root.resolve("users").resolve(AttachmentStore.userDirectoryKey(11L));
        Path bobDirectory = root.resolve("users").resolve(AttachmentStore.userDirectoryKey(22L));
        try (var children = Files.list(root)) {
            assertThat(children.map(path -> path.getFileName().toString())).containsExactly("users");
        }
        assertThat(aliceDirectory.resolve("202/2.png")).doesNotExist();
        assertThat(bobDirectory.resolve("101/1.png")).doesNotExist();
        try (InputStream aliceBody = store.open(alice);
                InputStream bobBody = store.open(bob)) {
            assertThat(aliceBody.readAllBytes()).containsExactly((byte) 1);
            assertThat(bobBody.readAllBytes()).containsExactly((byte) 2);
        }
    }

    @Test
    @DisplayName("현재 경로에 같은 이름의 파일이 있으면 저장을 실패시키고 기존 본문을 보존한다")
    void refusesDuplicateFileWithoutOverwritingExistingBytes() throws IOException {
        ChatAttachment photo = attachment(1L, 11L, 101L);
        store.save(photo, new ByteArrayInputStream(new byte[] {1}));

        assertThatThrownBy(() -> store.save(photo, new ByteArrayInputStream(new byte[] {2})))
                .isInstanceOf(ApiException.class);

        assertThat(Files.readAllBytes(fileOf(photo))).containsExactly((byte) 1);
    }

    @Test
    @DisplayName("본문 읽기가 실패하면 현재 경로에 조각 파일을 남기지 않는다")
    void removesPartialFileWhenBodyReadFails() {
        ChatAttachment photo = attachment(1L, 11L, 101L);

        assertThatThrownBy(() -> store.save(photo, failingAfterFirstByte())).isInstanceOf(ApiException.class);

        assertThat(fileOf(photo)).doesNotExist();
    }

    @Test
    @DisplayName("현재 파일이 없으면 읽기는 410이고 반복 삭제는 성공한다")
    void reportsGoneForMissingCurrentFileAndAllowsRepeatedDelete() {
        ChatAttachment photo = attachment(1L, 11L, 101L);

        assertThatThrownBy(() -> store.open(photo))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.ATTACHMENT_GONE));
        store.delete(photo);
        store.delete(photo);

        assertThat(fileOf(photo)).doesNotExist();
    }

    @Test
    @DisplayName("DB 저장 이름에 경로 조작이 있으면 저장과 읽기와 삭제를 모두 거절한다")
    void rejectsTraversalInStoredNameForEveryFileOperation() {
        ChatAttachment photo = attachment(1L, 11L, 101L);
        photo.nameStoredFile("../../other/2.png");

        assertThatThrownBy(() -> store.save(photo, new ByteArrayInputStream(new byte[] {1})))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.open(photo)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.delete(photo)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("사용자 디렉터리가 남의 디렉터리 링크면 저장과 읽기와 삭제를 거절한다")
    void refusesUserDirectorySymlink() throws IOException {
        ChatAttachment alice = attachment(1L, 11L, 101L);
        ChatAttachment bob = attachment(2L, 22L, 202L);
        store.save(bob, new ByteArrayInputStream(new byte[] {2}));
        Path users = root.resolve("users");
        Files.createSymbolicLink(
                users.resolve(AttachmentStore.userDirectoryKey(11L)),
                users.resolve(AttachmentStore.userDirectoryKey(22L)));

        assertThatThrownBy(() -> store.save(alice, new ByteArrayInputStream(new byte[] {1})))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.open(alice)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.delete(alice)).isInstanceOf(IllegalArgumentException.class);
        assertThat(fileOf(bob)).exists();
    }

    @Test
    @DisplayName("줄인 사본은 원본과 같은 폴더의 {id}.small.jpg 에 쓰고 같은 바이트로 읽는다")
    void storesSmallCopyBesideOriginal() throws IOException {
        ChatAttachment photo = attachment(1L, 11L, 101L);
        store.save(photo, new ByteArrayInputStream(new byte[] {1}));

        store.saveSmall(photo, new byte[] {7, 8, 9});

        assertThat(store.hasSmall(photo)).isTrue();
        assertThat(store.readSmall(photo)).containsExactly(7, 8, 9);
        Path small = fileOf(photo).resolveSibling("1.small.jpg");
        assertThat(Files.readAllBytes(small)).containsExactly(7, 8, 9);
        assertThat(fileOf(photo).resolveSibling("1.small.jpg.tmp")).doesNotExist();
    }

    @Test
    @DisplayName("사본이 이미 있으면 다시 쓰지 않는다")
    void keepsExistingSmallCopy() {
        ChatAttachment photo = attachment(1L, 11L, 101L);
        store.save(photo, new ByteArrayInputStream(new byte[] {1}));
        store.saveSmall(photo, new byte[] {7});

        store.saveSmall(photo, new byte[] {8});

        assertThat(store.readSmall(photo)).containsExactly(7);
    }

    @Test
    @DisplayName("지우기는 원본과 사본과 남은 임시 파일을 함께 지운다")
    void deletesOriginalAndSmallCopyTogether() throws IOException {
        ChatAttachment photo = attachment(1L, 11L, 101L);
        store.save(photo, new ByteArrayInputStream(new byte[] {1}));
        store.saveSmall(photo, new byte[] {7});
        Path temporary = fileOf(photo).resolveSibling("1.small.jpg.tmp");
        Files.write(temporary, new byte[] {9});

        store.delete(photo);

        assertThat(fileOf(photo)).doesNotExist();
        assertThat(fileOf(photo).resolveSibling("1.small.jpg")).doesNotExist();
        assertThat(temporary).doesNotExist();
        assertThat(store.hasSmall(photo)).isFalse();
        assertThatThrownBy(() -> store.readSmall(photo))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.ATTACHMENT_GONE));
    }

    @Test
    @DisplayName("원본을 지운 뒤에는 사본을 쓰지 않는다")
    void skipsSmallCopyAfterOriginalIsDeleted() {
        ChatAttachment photo = attachment(1L, 11L, 101L);
        store.save(photo, new ByteArrayInputStream(new byte[] {1}));
        store.delete(photo);

        store.saveSmall(photo, new byte[] {7});

        assertThat(store.hasSmall(photo)).isFalse();
        assertThat(fileOf(photo).resolveSibling("1.small.jpg")).doesNotExist();
    }

    @Test
    @DisplayName("사본 경로가 남의 파일 링크면 사본 쓰기와 읽기를 거절한다")
    void refusesSmallCopySymlink() throws IOException {
        ChatAttachment alice = attachment(1L, 11L, 101L);
        ChatAttachment bob = attachment(2L, 22L, 202L);
        store.save(alice, new ByteArrayInputStream(new byte[] {1}));
        store.save(bob, new ByteArrayInputStream(new byte[] {2}));
        Path link = fileOf(alice).resolveSibling("1.small.jpg");
        Files.createSymbolicLink(link, fileOf(bob));

        assertThatThrownBy(() -> store.saveSmall(alice, new byte[] {7}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.readSmall(alice)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readAllBytes(fileOf(bob))).containsExactly((byte) 2);
    }

    private Path fileOf(ChatAttachment attachment) {
        return root.resolve("users")
                .resolve(AttachmentStore.userDirectoryKey(attachment.uploadedByUserId()))
                .resolve(attachment.conversationId().toString())
                .resolve(attachment.storedName());
    }

    private static InputStream failingAfterFirstByte() {
        return new InputStream() {
            private boolean read;

            @Override
            public int read() throws IOException {
                if (!read) {
                    read = true;
                    return 1;
                }
                throw new IOException("body read failed");
            }
        };
    }

    private static ChatAttachment attachment(Long id, Long userId, Long conversationId) {
        ChatAttachment attachment = ChatAttachment.of(
                conversationId,
                userId,
                "photo.png",
                "image/png",
                1,
                Instant.now().plusSeconds(3600),
                Instant.now());
        ReflectionTestUtils.setField(attachment, "id", id);
        attachment.nameStoredFile(id + ".png");
        return attachment;
    }
}
