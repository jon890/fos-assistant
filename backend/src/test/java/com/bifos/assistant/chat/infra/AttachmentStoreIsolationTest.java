package com.bifos.assistant.chat.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.shared.error.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

/** 사용자별 저장과 옛 사본 복사의 파일 경계를 실제 파일 시스템에서 확인한다. */
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
        assertThat(aliceDirectory.resolve("202/2.png")).doesNotExist();
        assertThat(bobDirectory.resolve("101/1.png")).doesNotExist();
        try (InputStream aliceBody = store.open(alice); InputStream bobBody = store.open(bob)) {
            assertThat(aliceBody.readAllBytes()).containsExactly((byte) 1);
            assertThat(bobBody.readAllBytes()).containsExactly((byte) 2);
        }
    }

    @Test
    @DisplayName("옛 사본과 내용이 다르면 새 사본을 덮지 않고 복사 실패를 알린다")
    void refusesConflictingCopiesWithoutOverwritingEither() throws IOException {
        ChatAttachment photo = attachment(1L, 11L, 101L);
        store.save(photo, new ByteArrayInputStream(new byte[] {1}));
        Path legacy = legacyOf(photo);
        Files.createDirectories(legacy.getParent());
        Files.write(legacy, new byte[] {2});

        assertThatThrownBy(() -> store.prepare(photo)).isInstanceOf(UncheckedIOException.class);

        assertThat(Files.readAllBytes(fileOf(photo))).containsExactly((byte) 1);
        assertThat(Files.readAllBytes(legacy)).containsExactly((byte) 2);
    }

    @Test
    @DisplayName("이전 기간의 신규 사진은 옛 버전에서도 읽고 사용자 폴더에만 노출된다")
    void keepsNewUploadReadableAtLegacyPathDuringTransition() throws IOException {
        ChatAttachment photo = attachment(1L, 11L, 101L);

        store.save(photo, new ByteArrayInputStream(new byte[] {1, 2, 3}));

        assertThat(Files.readAllBytes(legacyOf(photo))).containsExactly((byte) 1, (byte) 2, (byte) 3);
        assertThat(Files.readAllBytes(fileOf(photo))).containsExactly((byte) 1, (byte) 2, (byte) 3);
        store.delete(photo);
        assertThat(legacyOf(photo)).doesNotExist();
        assertThat(fileOf(photo)).doesNotExist();
    }

    @Test
    @DisplayName("이전 기간이 끝나면 새 사진은 사용자 경로에만 쓰되 기존 옛 사진은 계속 복사한다")
    void stopsLegacyWritesWhileStillSupportingLegacyReads() throws IOException {
        AttachmentStore afterTransition = new AttachmentStore(
                new AttachmentProperties(root.toString(), "/images", null, null, null, false));
        ChatAttachment newPhoto = attachment(1L, 11L, 101L);
        afterTransition.save(newPhoto, new ByteArrayInputStream(new byte[] {1}));
        assertThat(fileOf(newPhoto)).exists();
        assertThat(legacyOf(newPhoto)).doesNotExist();

        ChatAttachment oldPhoto = attachment(2L, 22L, 202L);
        Files.createDirectories(legacyOf(oldPhoto).getParent());
        Files.write(legacyOf(oldPhoto), new byte[] {2});
        try (InputStream body = afterTransition.open(oldPhoto)) {
            assertThat(body.readAllBytes()).containsExactly((byte) 2);
        }
        assertThat(fileOf(oldPhoto)).exists();
    }

    @Test
    @DisplayName("옛 사본 저장이 실패하면 새 파일을 남기지 않고 이미 있던 옛 파일은 보존한다")
    void rollsBackNewFileWhenLegacyCopyCannotBeStored() throws IOException {
        ChatAttachment photo = attachment(1L, 11L, 101L);
        Path legacy = legacyOf(photo);
        Files.createDirectories(legacy.getParent());
        Files.write(legacy, new byte[] {9});

        assertThatThrownBy(() -> store.save(photo, new ByteArrayInputStream(new byte[] {1})))
                .isInstanceOf(ApiException.class);

        assertThat(fileOf(photo)).doesNotExist();
        assertThat(Files.readAllBytes(legacy)).containsExactly((byte) 9);
    }

    @Test
    @DisplayName("옛 사본을 복사한 뒤 만료 삭제는 두 사본을 함께 지운다")
    void deletesLegacyAndPrivateCopiesTogether() throws IOException {
        ChatAttachment photo = attachment(1L, 11L, 101L);
        Path legacy = legacyOf(photo);
        Files.createDirectories(legacy.getParent());
        Files.write(legacy, new byte[] {1});
        store.prepare(photo);

        store.delete(photo);
        store.prepare(photo);

        assertThat(legacy).doesNotExist();
        assertThat(fileOf(photo)).doesNotExist();
    }

    @Test
    @DisplayName("DB 저장 이름에 경로 조작이 있으면 읽기 복사와 삭제를 모두 거절한다")
    void rejectsTraversalInStoredNameForEveryFileOperation() {
        ChatAttachment photo = attachment(1L, 11L, 101L);
        photo.nameStoredFile("../../other/2.png");

        assertThatThrownBy(() -> store.save(photo, new ByteArrayInputStream(new byte[] {1})))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.open(photo)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.prepare(photo)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.delete(photo)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("옛 파일이 다른 사용자의 사진을 가리키는 링크면 복사하지 않는다")
    void refusesLegacySymlinkToAnotherUsersImage() throws IOException {
        ChatAttachment alice = attachment(1L, 11L, 101L);
        ChatAttachment bob = attachment(2L, 22L, 202L);
        store.save(bob, new ByteArrayInputStream(new byte[] {2}));
        Path legacy = legacyOf(alice);
        Files.createDirectories(legacy.getParent());
        Files.createSymbolicLink(legacy, fileOf(bob));

        assertThatThrownBy(() -> store.prepare(alice)).isInstanceOf(IllegalArgumentException.class);
        assertThat(fileOf(alice)).doesNotExist();
        assertThat(fileOf(bob)).exists();
    }

    @Test
    @DisplayName("사용자 디렉터리가 남의 디렉터리 링크면 저장과 읽기를 거절한다")
    void refusesUserDirectorySymlink() throws IOException {
        ChatAttachment alice = attachment(1L, 11L, 101L);
        ChatAttachment bob = attachment(2L, 22L, 202L);
        store.save(bob, new ByteArrayInputStream(new byte[] {2}));
        Path users = root.resolve("users");
        Files.createSymbolicLink(users.resolve(AttachmentStore.userDirectoryKey(11L)),
                users.resolve(AttachmentStore.userDirectoryKey(22L)));

        assertThatThrownBy(() -> store.save(alice, new ByteArrayInputStream(new byte[] {1})))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.open(alice)).isInstanceOf(IllegalArgumentException.class);
        assertThat(fileOf(bob)).exists();
    }

    private Path fileOf(ChatAttachment attachment) {
        return root.resolve("users").resolve(AttachmentStore.userDirectoryKey(attachment.uploadedByUserId()))
                .resolve(attachment.conversationId().toString()).resolve(attachment.storedName());
    }

    private Path legacyOf(ChatAttachment attachment) {
        return root.resolve(attachment.conversationId().toString()).resolve(attachment.storedName());
    }

    private static ChatAttachment attachment(Long id, Long userId, Long conversationId) {
        ChatAttachment attachment = ChatAttachment.of(
                conversationId, userId, "photo.png", "image/png", 1, Instant.now().plusSeconds(3600), Instant.now());
        ReflectionTestUtils.setField(attachment, "id", id);
        attachment.nameStoredFile(id + ".png");
        return attachment;
    }
}
