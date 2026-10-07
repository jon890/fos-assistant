package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.shared.error.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxAttachmentDirectoryTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("키는 주인 문자열의 SHA-256 이고 plugin 과 같은 고정 기대값을 낸다")
    void keyMatchesTheAttachmentStoreAndPluginGoldenVector() {
        assertThat(SandboxAttachmentDirectory.key("u11"))
                .isEqualTo("92ec86fa88925dabc1026bfc9335f5f6848886c98586c005f4a0c02716e3bbab");
    }

    @Test
    @DisplayName("주인 형식이 plugin 계약과 다르면 경로를 만들지 않는다")
    void rejectsOwnersOutsideThePluginContract() {
        for (String owner : new String[] {null, "", "U1", "1u", "a/b", "..", "a".repeat(65)}) {
            assertThatThrownBy(() -> SandboxAttachmentDirectory.key(owner))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(SandboxAttachmentDirectory.key("a".repeat(64))).hasSize(64);
    }

    @Test
    @DisplayName("users 가 없어도 만들고, 이미 있으면 그대로 둔다")
    void createsMissingDirectoriesAndKeepsExistingOnes() throws IOException {
        SandboxAttachmentDirectory directory = new SandboxAttachmentDirectory(root.toString());
        Path expected = root.resolve("users").resolve(SandboxAttachmentDirectory.key("u1"));

        directory.ensure("u1");
        Files.writeString(expected.resolve("kept.txt"), "kept");
        directory.ensure("u1");

        assertThat(expected).isDirectory();
        assertThat(expected.resolve("kept.txt")).hasContent("kept");
    }

    @Test
    @DisplayName("주인 디렉터리가 다른 사용자 디렉터리로 가는 링크이면 거절한다")
    void rejectsAnOwnerDirectoryLinkedToAnotherUser() throws IOException {
        Path users = Files.createDirectory(root.resolve("users"));
        Path other = Files.createDirectory(users.resolve(SandboxAttachmentDirectory.key("u2")));
        Files.createSymbolicLink(users.resolve(SandboxAttachmentDirectory.key("u1")), other);

        assertRejected(new SandboxAttachmentDirectory(root.toString()), "u1");
    }

    @Test
    @DisplayName("users 가 바깥으로 가는 링크이면 거절하고 바깥에 만들지 않는다")
    void rejectsALinkedUsersDirectory() throws IOException {
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path attachments = Files.createDirectory(root.resolve("attachments"));
        Files.createSymbolicLink(attachments.resolve("users"), outside);

        assertRejected(new SandboxAttachmentDirectory(attachments.toString()), "u1");
        assertThat(outside.resolve(SandboxAttachmentDirectory.key("u1"))).doesNotExist();
    }

    @Test
    @DisplayName("첨부 루트 자체가 링크이거나 없으면 거절한다")
    void rejectsALinkedOrMissingRoot() throws IOException {
        Path real = Files.createDirectory(root.resolve("real"));
        Path alias = Files.createSymbolicLink(root.resolve("alias"), real);

        assertRejected(new SandboxAttachmentDirectory(alias.toString()), "u1");
        assertRejected(new SandboxAttachmentDirectory(root.resolve("missing").toString()), "u1");
        assertThat(real.resolve("users")).doesNotExist();
        assertThat(root.resolve("missing")).doesNotExist();
    }

    @Test
    @DisplayName("주인 디렉터리 자리에 파일이 있으면 거절한다")
    void rejectsAFileInPlaceOfTheDirectory() throws IOException {
        Path users = Files.createDirectory(root.resolve("users"));
        Files.writeString(users.resolve(SandboxAttachmentDirectory.key("u1")), "not a directory");

        assertRejected(new SandboxAttachmentDirectory(root.toString()), "u1");
    }

    private static void assertRejected(SandboxAttachmentDirectory directory, String owner) {
        assertThatThrownBy(() -> directory.ensure(owner)).isInstanceOfSatisfying(HermesRequestRejected.class, ex -> {
            assertThat(ex.code()).isEqualTo(ErrorCode.AGENT_SANDBOX_UNAVAILABLE);
            assertThat(ex.status()).isEqualTo(409);
        });
    }
}
