package com.bifos.assistant.workspace.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.workspace.domain.WorkspaceEntry;
import com.bifos.assistant.workspace.domain.WorkspaceEntryKind;
import com.bifos.assistant.workspace.domain.WorkspaceOpenedFile;
import com.bifos.assistant.workspace.domain.WorkspacePath;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Assumptions;

/**
 * #359 재현이다. 운영 이미지(musl)가 타는 대체 경로({@code checkedPath})로 넣기 도구와 같은 순서(stat 뒤 open)를 되풀이하면서
 * 공격자 공간의 중간 디렉터리를 링크와 계속 바꾼다. 다른 사용자 본문이나 실행 공간 밖 본문이 한 번도 나오지 않아야 한다.
 *
 * <p>스레드 경쟁이라 시간이 들고 결과가 확률적이어서 기본 검사에서 빼고 {@code linkRaceTest} 가 돌린다. 시간은
 * {@code -PlinkRace.seconds} 로 바꾼다.
 */
@Tag("link-race")
class WorkspaceTreeLinkRaceTest {

    @TempDir
    Path tmp;


    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"other-user,MOVE", "outside-root,MOVE", "other-user,EXCHANGE", "outside-root,EXCHANGE"})
    @DisplayName("대체 경로에서 중간 디렉터리를 링크와 계속 바꿔도 공격자 공간 밖 본문을 주지 않는다")
    void fallbackNeverReturnsBodyOutsideOwner(String targetKind, LinkSwapRace.Mode mode) throws Exception {
        Assumptions.assumeTrue(mode == LinkSwapRace.Mode.MOVE || LinkSwapRace.exchangeAvailable(), "python3 이 없다");
        Path root = Files.createDirectory(tmp.resolve("root"));
        Path owner = Files.createDirectory(root.resolve("u1"));
        Path target = targetKind.equals("other-user")
            ? Files.createDirectories(root.resolve("u2/docs"))
            : Files.createDirectories(tmp.resolve("outside"));
        WorkspacePath path = WorkspacePath.parse("docs/secret.txt");
        LinkSwapRace.Tally tally = new LinkSwapRace.Tally();
        long deadline = System.nanoTime() + LinkRaceSettings.nanos();

        LinkSwapRace race = new LinkSwapRace(owner, target, mode);
        try (race) {
            race.start();
            while (System.nanoTime() < deadline) {
                readLikeFilePut(owner, path, tally);
            }
        }
        long swaps = race.swaps();

        System.out.println(tally.summary("fallback " + targetKind + " " + mode, swaps));
        assertThat(tally.leaked()).as(tally.summary("fallback " + targetKind + " " + mode, swaps)).isZero();
    }


    /** {@code WorkspaceSandboxFiles.read} 와 같은 순서다. 대체 경로만 강제한다. */
    private static void readLikeFilePut(Path owner, WorkspacePath path, LinkSwapRace.Tally tally) {
        try {
            Optional<WorkspaceEntry> entry = WorkspaceTree.stat(owner, path, true, () -> {
            });
            if (entry.isEmpty() || entry.get().kind() != WorkspaceEntryKind.FILE || !entry.get().readable()) {
                tally.add("stat-missing");
                return;
            }
            WorkspaceOpenedFile opened = WorkspaceTree.open(owner, path, true, () -> {
            });
            try (InputStream body = opened.body()) {
                tally.body(body.readAllBytes());
            }
        } catch (ApiException ex) {
            tally.add("open-" + ex.code().name());
        } catch (IOException ex) {
            tally.add("io-" + ex.getClass().getSimpleName());
        }
    }
}
