package com.bifos.assistant.workspace.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.workspace.domain.WorkspaceEntry;
import com.bifos.assistant.workspace.domain.WorkspaceEntryKind;
import com.bifos.assistant.workspace.domain.WorkspaceListing;
import com.bifos.assistant.workspace.domain.WorkspaceOpenedFile;
import com.bifos.assistant.workspace.domain.WorkspacePath;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** 실행 공간을 읽을 때 링크와 특수 파일과 남의 공간을 어떻게 다루는지 실제 임시 디렉터리로 본다. */
class WorkspaceTreeTest {

    @TempDir
    Path root;

    private Path owner;
    private Path sibling;

    @BeforeEach
    void setUp() throws IOException {
        owner = Files.createDirectory(root.resolve("u1"));
        sibling = Files.createDirectory(root.resolve("u2"));
        Files.writeString(sibling.resolve("secret.txt"), "sibling secret");
    }

    @Test
    @DisplayName("디렉터리를 먼저 이름 순서로 주고 파일만 크기를 채운다")
    void listsDirectoriesFirstInNameOrder() throws IOException {
        Files.createDirectory(owner.resolve("b-dir"));
        Files.createDirectory(owner.resolve("a-dir"));
        Files.writeString(owner.resolve("c.txt"), "abc");
        Files.writeString(owner.resolve("B.txt"), "");

        WorkspaceListing listing = list(owner, "").orElseThrow();

        assertThat(listing.entries())
                .extracting(WorkspaceEntry::name)
                .containsExactly("a-dir", "b-dir", "B.txt", "c.txt");
        assertThat(listing.entries())
                .extracting(WorkspaceEntry::kind)
                .containsExactly(
                        WorkspaceEntryKind.DIRECTORY,
                        WorkspaceEntryKind.DIRECTORY,
                        WorkspaceEntryKind.FILE,
                        WorkspaceEntryKind.FILE);
        assertThat(listing.entries()).extracting(WorkspaceEntry::size).containsExactly(null, null, 0L, 3L);
        assertThat(listing.entries()).allMatch(WorkspaceEntry::readable);
        assertThat(listing.truncated()).isFalse();
    }

    @Test
    @DisplayName("1,001개 가운데 1,000개만 주고 잘렸다고 알린다")
    void truncatesAtLimit() throws IOException {
        Path many = Files.createDirectory(owner.resolve("many"));
        for (int i = 0; i < 1_001; i++) {
            Files.createFile(many.resolve("f" + i));
        }

        WorkspaceListing listing = list(owner, "many").orElseThrow();

        assertThat(listing.entries()).hasSize(1_000);
        assertThat(listing.truncated()).isTrue();
        assertThat(listing.path()).isEqualTo("many");
    }

    @Test
    @DisplayName("주인 디렉터리가 없으면 빈 경로에만 빈 목록을 준다")
    void listsEmptyWhenOwnerDirectoryIsMissing() throws IOException {
        Path missing = root.resolve("u9");

        assertThat(list(missing, "").orElseThrow().entries()).isEmpty();
        assertThat(list(missing, "a")).isEmpty();
        assertThat(WorkspaceTree.isDirectoryNoFollow(missing)).isFalse();
    }

    @Test
    @DisplayName("대체 경로에서 연 뒤 중간 디렉터리가 형제 공간 링크로 바뀌면 본문과 목록을 주지 않는다")
    void rejectsFallbackReadWhenDirectoryIsSwappedToSiblingLink() throws IOException {
        Path out = Files.createDirectory(owner.resolve("out"));
        Files.writeString(out.resolve("secret.txt"), "own");
        Files.writeString(out.resolve("mine.txt"), "own");
        Files.createDirectory(sibling.resolve("private"));
        Files.writeString(sibling.resolve("private/secret.txt"), "sibling secret");
        Runnable swap = () -> swapToLink(out, sibling.resolve("private"));
        WorkspacePath secret = WorkspacePath.parse("out/secret.txt");
        WorkspacePath outDir = WorkspacePath.parse("out");

        assertCode(() -> WorkspaceTree.open(owner, secret, true, swap), ErrorCode.WORKSPACE_ENTRY_NOT_FOUND);
        restore(out);
        assertThat(WorkspaceTree.list(owner, outDir, 1_000, true, swap)).isEmpty();
        restore(out);
        assertThat(WorkspaceTree.stat(owner, secret, true, swap)).isEmpty();
        restore(out);
        assertThat(WorkspaceTree.list(owner, outDir, 1_000, true, () -> {}))
                .map(listing ->
                        listing.entries().stream().map(WorkspaceEntry::name).toList())
                .contains(List.of("mine.txt", "secret.txt"));
    }

    @Test
    @DisplayName("연 뒤 파일이 커져도 본문은 연 시점의 크기까지만 준다")
    void limitsBodyToSizeAtOpen() throws IOException {
        Path log = Files.writeString(owner.resolve("run.log"), "1234");

        WorkspaceOpenedFile opened = open(owner, "run.log");
        Files.writeString(log, "5678", StandardOpenOption.APPEND);

        try (InputStream body = opened.body()) {
            assertThat(new String(body.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("1234");
        }
        assertThat(opened.size()).isEqualTo(4L);
    }

    @Test
    @DisplayName("공간 밖을 가리키는 링크 파일은 LINK 이고 읽지 않는다")
    void doesNotFollowFileLink() throws IOException {
        Path outside = Files.writeString(root.resolve("outside.txt"), "outside");
        Files.createSymbolicLink(owner.resolve("link.txt"), outside);

        WorkspaceEntry entry = list(owner, "").orElseThrow().entries().get(0);

        assertThat(entry.kind()).isEqualTo(WorkspaceEntryKind.LINK);
        assertThat(entry.readable()).isFalse();
        assertThat(entry.size()).isNull();
        assertCode(() -> open(owner, "link.txt"), ErrorCode.WORKSPACE_ENTRY_NOT_FOUND);
    }

    @Test
    @DisplayName("형제 주인 디렉터리를 가리키는 링크 디렉터리 아래는 목록과 본문 모두 닿지 않는다")
    void doesNotFollowDirectoryLinkToSibling() throws IOException {
        Path link = Files.createSymbolicLink(owner.resolve("peek"), sibling);

        WorkspaceListing top = list(owner, "").orElseThrow();
        assertThat(top.entries()).extracting(WorkspaceEntry::name).containsExactly("peek");
        assertThat(top.entries().get(0).kind()).isEqualTo(WorkspaceEntryKind.LINK);
        assertThat(list(owner, "peek")).isEmpty();
        assertCode(() -> open(owner, "peek/secret.txt"), ErrorCode.WORKSPACE_ENTRY_NOT_FOUND);
        assertThat(WorkspaceTree.stat(owner, WorkspacePath.parse("peek/secret.txt")))
                .isEmpty();
        assertThat(WorkspaceTree.isDirectoryNoFollow(link)).isFalse();
    }

    @Test
    @DisplayName("주인 디렉터리 자체가 링크면 비어 있다")
    void refusesOwnerDirectoryLink() throws IOException {
        Path linkedOwner = Files.createSymbolicLink(root.resolve("u3"), sibling);

        assertThat(list(linkedOwner, "")).isEmpty();
        assertThat(WorkspaceTree.isDirectoryNoFollow(linkedOwner)).isFalse();
        assertCode(() -> open(linkedOwner, "secret.txt"), ErrorCode.WORKSPACE_ENTRY_NOT_FOUND);
    }

    @Test
    @DisplayName("하드 링크가 둘 이상인 파일은 읽을 수 없다")
    void refusesHardLinkedFile() throws IOException {
        Path original = Files.writeString(owner.resolve("a.txt"), "shared");
        Files.createLink(owner.resolve("b.txt"), original);

        assertThat(list(owner, "").orElseThrow().entries()).allSatisfy(entry -> {
            assertThat(entry.kind()).isEqualTo(WorkspaceEntryKind.FILE);
            assertThat(entry.readable()).isFalse();
        });
        assertCode(() -> open(owner, "a.txt"), ErrorCode.WORKSPACE_ENTRY_UNREADABLE);
    }

    @Test
    @Timeout(5)
    @DisplayName("FIFO 는 OTHER 이고 열지 않고 없는 것으로 답한다")
    void refusesFifoWithoutBlocking() throws Exception {
        Path fifo = owner.resolve("pipe");
        Assumptions.assumeTrue(mkfifo(fifo), "mkfifo 가 없는 환경이다");

        assertThat(list(owner, "").orElseThrow().entries().get(0).kind()).isEqualTo(WorkspaceEntryKind.OTHER);
        assertCode(() -> open(owner, "pipe"), ErrorCode.WORKSPACE_ENTRY_NOT_FOUND);
    }

    @Test
    @DisplayName("일반 파일을 열면 크기와 본문을 준다")
    void opensRegularFile() throws IOException {
        Files.createDirectory(owner.resolve("out"));
        Files.writeString(owner.resolve("out/보고서.csv"), "a,b\n1,2\n");

        WorkspaceOpenedFile opened = open(owner, "out/보고서.csv");

        try (InputStream body = opened.body()) {
            assertThat(new String(body.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("a,b\n1,2\n");
        }
        assertThat(opened.size()).isEqualTo(8L);
        assertThat(WorkspaceTree.stat(owner, WorkspacePath.parse("out/보고서.csv")))
                .map(WorkspaceEntry::size)
                .contains(8L);
        assertCode(() -> open(owner, "out"), ErrorCode.WORKSPACE_ENTRY_NOT_FOUND);
        assertCode(() -> open(owner, "out/missing.csv"), ErrorCode.WORKSPACE_ENTRY_NOT_FOUND);
    }

    @Test
    @DisplayName("소유자 권한을 뺀 파일은 읽을 수 없다")
    void refusesFileWithoutPermission() throws IOException {
        Path locked = Files.writeString(owner.resolve("locked.txt"), "private");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        Assumptions.assumeFalse(Files.isReadable(locked), "root 로 돌면 권한을 빼도 읽힌다");

        WorkspaceEntry entry = list(owner, "").orElseThrow().entries().get(0);

        assertThat(entry.kind()).isEqualTo(WorkspaceEntryKind.FILE);
        assertThat(entry.readable()).isFalse();
        assertCode(() -> open(owner, "locked.txt"), ErrorCode.WORKSPACE_ENTRY_UNREADABLE);
    }

    private static Optional<WorkspaceListing> list(Path ownerDir, String path) throws IOException {
        return WorkspaceTree.list(ownerDir, WorkspacePath.parse(path), 1_000);
    }

    private static WorkspaceOpenedFile open(Path ownerDir, String path) throws IOException {
        return WorkspaceTree.open(ownerDir, WorkspacePath.parse(path));
    }

    private static void assertCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(expected);
    }

    /** 디렉터리를 옆으로 옮기고 그 자리에 링크를 둔다. 에이전트가 경로를 바꾸는 경쟁을 흉내 낸다. */
    private void swapToLink(Path dir, Path target) {
        try {
            Files.move(dir, owner.resolve("out-before"));
            Files.createSymbolicLink(dir, target);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** 바꿔 둔 링크를 지우고 원래 디렉터리를 제자리로 돌린다. */
    private void restore(Path dir) throws IOException {
        Files.delete(dir);
        Files.move(owner.resolve("out-before"), dir);
    }

    private static boolean mkfifo(Path fifo) throws InterruptedException {
        try {
            Process process = new ProcessBuilder("mkfifo", fifo.toString())
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0 && Files.exists(fifo);
        } catch (IOException ex) {
            return false;
        }
    }
}
