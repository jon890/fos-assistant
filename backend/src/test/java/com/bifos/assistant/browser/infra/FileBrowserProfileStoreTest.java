package com.bifos.assistant.browser.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBrowserProfileStoreTest {

    private static final String KEY = "0123456789abcdef".repeat(4);

    @TempDir
    Path temp;

    private Path root;
    private FileBrowserProfileStore store;

    @BeforeEach
    void setUp() {
        root = temp.resolve("profiles");
        store = new FileBrowserProfileStore(new BrowserProperties(
                false,
                null,
                null,
                null,
                null,
                root.toString(),
                null,
                null,
                1024,
                null,
                null,
                null,
                2,
                Duration.ofMinutes(10),
                Duration.ofSeconds(30)));
    }

    @Test
    @DisplayName("만들면 키 이름의 디렉터리가 주인만 쓰는 권한으로 생기고 다시 만들어도 그대로다")
    void ensuresOwnerOnlyDirectory() throws Exception {
        store.ensure(KEY);
        Files.writeString(root.resolve(KEY).resolve("Cookies"), "x");

        store.ensure(KEY);

        Path dir = root.resolve(KEY);
        assertThat(dir).isDirectory();
        assertThat(dir.resolve("Cookies")).exists();
        if (Files.getFileStore(dir).supportsFileAttributeView("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)))
                    .isEqualTo("rwx------");
        }
    }

    @Test
    @DisplayName("새 프로필에는 이전 세션을 이어서 여는 설정 파일을 주인만 읽는 권한으로 써 둔다")
    void writesSessionRestorePreferences() throws Exception {
        store.ensure(KEY);

        Path preferences = root.resolve(KEY).resolve("Default/Preferences");
        assertThat(preferences).hasContent("{\"session\":{\"restore_on_startup\":1}}");
        if (Files.getFileStore(preferences).supportsFileAttributeView("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(preferences)))
                    .isEqualTo("rw-------");
        }
    }

    @Test
    @DisplayName("설정 파일이 이미 있으면 내용을 그대로 두고, 설정 파일이 없던 기존 프로필에는 써 둔다")
    void keepsExistingPreferences() throws Exception {
        Files.createDirectories(root.resolve(KEY).resolve("Default"));
        store.ensure(KEY);
        Path preferences = root.resolve(KEY).resolve("Default/Preferences");
        assertThat(preferences).exists();

        Files.writeString(preferences, "{\"chrome\":\"owned\"}");
        store.ensure(KEY);

        assertThat(preferences).hasContent("{\"chrome\":\"owned\"}");
    }

    @Test
    @DisplayName("설정 파일 자리의 링크를 따라가지 않아 링크가 가리키는 파일은 그대로다")
    void doesNotFollowPreferencesLink() throws Exception {
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.writeString(outside.resolve("target"), "keep");
        Files.createDirectories(root.resolve(KEY).resolve("Default"));
        Files.createSymbolicLink(root.resolve(KEY).resolve("Default/Preferences"), outside.resolve("target"));

        store.ensure(KEY);

        assertThat(outside.resolve("target")).hasContent("keep");
    }

    @Test
    @DisplayName("지우면 디렉터리를 통째로 지우고 없는 디렉터리를 지워도 성공이다")
    void deletesWholeDirectory() throws Exception {
        store.ensure(KEY);
        Files.createDirectories(root.resolve(KEY).resolve("Default/Cache"));
        Files.writeString(root.resolve(KEY).resolve("Default/Cache/data"), "x");

        store.delete(KEY);
        store.delete(KEY);

        assertThat(root.resolve(KEY)).doesNotExist();
    }

    @Test
    @DisplayName("지울 때 디렉터리 안의 링크를 따라가지 않아 링크가 가리키는 곳은 남는다")
    void deletesWithoutFollowingLinks() throws Exception {
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.writeString(outside.resolve("keep.txt"), "keep");
        store.ensure(KEY);
        Files.createSymbolicLink(root.resolve(KEY).resolve("escape"), outside);

        store.delete(KEY);

        assertThat(root.resolve(KEY)).doesNotExist();
        assertThat(outside.resolve("keep.txt")).hasContent("keep");
    }

    @Test
    @DisplayName("키 자리에 링크가 있으면 링크만 지우고 만들기는 거절한다")
    void treatsLinkedProfileAsLinkOnly() throws Exception {
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.writeString(outside.resolve("keep.txt"), "keep");
        Files.createDirectories(root);
        Files.createSymbolicLink(root.resolve(KEY), outside);

        assertThatThrownBy(() -> store.ensure(KEY)).isInstanceOf(IllegalStateException.class);

        store.delete(KEY);
        assertThat(Files.exists(root.resolve(KEY), LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(outside.resolve("keep.txt")).hasContent("keep");
    }

    @Test
    @DisplayName("소문자 16진수 64자리가 아닌 키는 만들기와 지우기 모두 거절한다")
    void rejectsMalformedKeys() {
        for (String key : new String[] {"../" + KEY.substring(3), KEY.toUpperCase(), KEY.substring(1), "", null}) {
            assertThatThrownBy(() -> store.ensure(key)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.delete(key)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(root).doesNotExist();
    }
}
