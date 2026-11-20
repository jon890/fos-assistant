package com.bifos.assistant.workspace.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.mcp.application.port.SandboxFiles;
import com.bifos.assistant.mcp.application.port.SandboxFiles.SandboxFile;
import com.bifos.assistant.mcp.application.port.SandboxFiles.Status;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.workspace.config.WorkspaceProperties;
import com.bifos.assistant.workspace.infra.LinkRaceSettings;
import com.bifos.assistant.workspace.infra.LinkSwapRace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Assumptions;

/**
 * #359 재현이다. 넣기 도구가 부르는 {@link WorkspaceSandboxFiles#read} 를 그대로 되풀이한다. 지금 JDK 가
 * {@link SecureDirectoryStream} 을 주면 핸들 경로를, 주지 않으면(운영 이미지 musl) 대체 경로를 탄다. 운영과 같은 경로를 보려면
 * Alpine JDK 21 에서 돌린다. 결과 줄의 {@code read fallback} 이 그 경로다.
 */
@Tag("link-race")
class WorkspaceSandboxFilesLinkRaceTest {

    @TempDir
    Path tmp;


    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"other-user,MOVE", "outside-root,MOVE", "other-user,EXCHANGE", "outside-root,EXCHANGE"})
    @DisplayName("넣기 도구의 읽기는 중간 디렉터리를 링크와 계속 바꿔도 공격자 공간 밖 본문을 주지 않는다")
    void filePutNeverReturnsBodyOutsideOwner(String targetKind, LinkSwapRace.Mode mode) throws Exception {
        Assumptions.assumeTrue(mode == LinkSwapRace.Mode.MOVE || LinkSwapRace.exchangeAvailable(), "python3 이 없다");
        Path root = Files.createDirectory(tmp.resolve("root"));
        Path owner = Files.createDirectory(root.resolve("u1"));
        Path target = targetKind.equals("other-user")
            ? Files.createDirectories(root.resolve("u2/docs"))
            : Files.createDirectories(tmp.resolve("outside"));
        SandboxFiles files = new WorkspaceSandboxFiles(
            LiveProperties.fixed(WorkspaceProperties.class, new WorkspaceProperties(root.toString(), "")));
        String handle;
        try (var stream = Files.newDirectoryStream(root)) {
            handle = stream instanceof SecureDirectoryStream ? "secure" : "fallback";
        }
        LinkSwapRace.Tally tally = new LinkSwapRace.Tally();
        long deadline = System.nanoTime() + LinkRaceSettings.nanos();

        LinkSwapRace race = new LinkSwapRace(owner, target, mode);
        try (race) {
            race.start();
            while (System.nanoTime() < deadline) {
                SandboxFile file = files.read("u1", "docs/secret.txt", 1024);
                if (file.status() == Status.FOUND) {
                    tally.body(file.bytes());
                } else {
                    tally.add(file.status().name());
                }
            }
        }
        long swaps = race.swaps();

        String label = "read " + handle + " " + targetKind + " " + mode + " java=" + System.getProperty("java.version");
        System.out.println(tally.summary(label, swaps));
        assertThat(tally.leaked()).as(tally.summary(label, swaps)).isZero();
    }
}
