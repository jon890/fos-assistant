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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * #359 의 링크 바꿔치기 경쟁을 합성 환경에서 되풀이하는 회귀 guard 다. 넣기 도구가 부르는 {@link WorkspaceSandboxFiles#read}
 * 를 그대로 되풀이하면서, 공격자 공간의 중간 디렉터리를 다른 사용자 폴더나 실행 공간 밖을 가리키는 링크와 계속 바꾼다.
 *
 * <p>고치기 전(운영 musl 이미지의 대체 경로)에는 다른 사용자 본문이 샜다. 고친 뒤 {@link SecureDirectoryStream} 만 쓰는
 * 핸들 경로(glibc)에서는 핸들로 따라와 한 건도 새지 않는다. {@link SecureDirectoryStream} 이 없는 운영체제에서는 파일 공간이
 * 꺼져(fail-closed) 모두 {@code MISSING} 이라 역시 새지 않는다. 운영과 같은 핸들 경로를 보려면 glibc 에서 돌린다.
 *
 * <p>스레드 경쟁이라 시간이 들고 결과가 확률적이어서 기본 검사에서 빼고 {@code linkRaceTest} 가 돌린다. 시간은
 * {@code -PlinkRace.seconds} 로 바꾼다.
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
            handle = stream instanceof SecureDirectoryStream ? "secure" : "fail-closed";
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
