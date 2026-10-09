package com.bifos.assistant.workspace.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.user.application.UserDisplayNameService;
import com.bifos.assistant.workspace.application.model.WorkspaceSpaceKind;
import com.bifos.assistant.workspace.application.model.WorkspaceSpaceUsage;
import com.bifos.assistant.workspace.application.model.WorkspaceUsageReport;
import com.bifos.assistant.workspace.infra.WorkspaceProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 관리자 용량이 실행 공간을 어떻게 고르고 세는지 실제 임시 디렉터리로 본다. 계약은 {@code backend/docs/code-architecture.md} 의 「관리자 용량」 이다. */
class WorkspaceUsageServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path root;

    @TempDir
    Path outside;

    private final UserDisplayNameService userNames = mock(UserDisplayNameService.class);
    private final AgentService agents = mock(AgentService.class);

    @BeforeEach
    void setUp() {
        when(userNames.find(1L)).thenReturn("아빠");
        when(userNames.find(2L)).thenReturn("아이");
        Agent agent = mock(Agent.class);
        when(agent.name()).thenReturn("집안일 비서");
        when(agents.byIds(List.of(3L))).thenReturn(Map.of(3L, agent));
    }

    @Test
    @DisplayName("u 와 a 번호 디렉터리만 이름과 함께 크기가 큰 순서로 준다")
    void reportsNumberedSpacesLargestFirst() throws IOException {
        write("u1/report.txt", 300);
        write("u2/note.txt", 100);
        write("a3/out/result.bin", 200);
        write("tmp/cache.bin", 1_000);
        write("u4", 50);

        WorkspaceUsageReport report = service(FIXED, 200_000).report();

        assertThat(report.available()).isTrue();
        assertThat(report.spaces())
                .containsExactly(
                        new WorkspaceSpaceUsage(WorkspaceSpaceKind.USER, 1, "아빠", 300, 1, false),
                        new WorkspaceSpaceUsage(WorkspaceSpaceKind.AGENT, 3, "집안일 비서", 200, 2, false),
                        new WorkspaceSpaceUsage(WorkspaceSpaceKind.USER, 2, "아이", 100, 1, false));
    }

    @Test
    @DisplayName("공간 안의 링크는 항목으로만 세고 가리키는 파일의 크기를 넣지 않으며 루트 바로 아래의 링크 디렉터리는 세지 않는다")
    void doesNotFollowLinks() throws IOException {
        write("u1/own.txt", 10);
        Path bigFile = Files.write(outside.resolve("big.bin"), new byte[5_000]);
        Path outsideDir = Files.createDirectories(outside.resolve("elsewhere"));
        Files.write(outsideDir.resolve("inner.bin"), new byte[7_000]);
        Files.createSymbolicLink(root.resolve("u1/big-link"), bigFile);
        Files.createSymbolicLink(root.resolve("u1/dir-link"), outsideDir);
        Files.createSymbolicLink(root.resolve("u9"), outsideDir);

        WorkspaceUsageReport report = service(FIXED, 200_000).report();

        assertThat(report.spaces())
                .containsExactly(new WorkspaceSpaceUsage(WorkspaceSpaceKind.USER, 1, "아빠", 10, 3, false));
    }

    @Test
    @DisplayName("항목이 상한을 넘는 공간은 상한까지만 세고 일부로 표시하며 상한과 같은 공간은 다 센다")
    void marksSpacesOverEntryLimitAsPartial() throws IOException {
        write("u1/a.txt", 10);
        write("u1/b.txt", 10);
        write("u1/c.txt", 10);
        write("u2/a.txt", 10);
        write("u2/b.txt", 10);

        WorkspaceUsageReport report = service(FIXED, 2).report();

        assertThat(report.spaces())
                .extracting(WorkspaceSpaceUsage::id, WorkspaceSpaceUsage::entries, WorkspaceSpaceUsage::partial)
                .containsExactlyInAnyOrder(tuple(1L, 2L, true), tuple(2L, 2L, false));
    }

    @Test
    @DisplayName("요청 전체의 시간이 걷는 도중에 지나면 그 공간은 센 데까지 일부로 주고 남은 공간은 0 과 일부로 준다")
    void stopsMidWalkAndSkipsSpacesAfterBudget() throws IOException {
        for (int i = 0; i < 5; i++) {
            write("u1/f" + i + ".txt", 10);
            write("a3/f" + i + ".txt", 10);
        }
        Files.createDirectories(root.resolve("u2"));

        WorkspaceUsageReport report = new WorkspaceUsageService(
                        LiveProperties.fixed(WorkspaceProperties.class, new WorkspaceProperties(root.toString(), "")),
                        userNames,
                        agents,
                        new SteppingClock(NOW),
                        200_000,
                        Duration.ofMillis(3))
                .report();

        WorkspaceSpaceUsage first = space(report, WorkspaceSpaceKind.USER, 1);
        assertThat(first.entries()).isBetween(1L, 4L);
        assertThat(first.bytes()).isEqualTo(first.entries() * 10);
        assertThat(first.partial()).isTrue();
        assertThat(space(report, WorkspaceSpaceKind.USER, 2))
                .isEqualTo(new WorkspaceSpaceUsage(WorkspaceSpaceKind.USER, 2, "아이", 0, 0, true));
        assertThat(space(report, WorkspaceSpaceKind.AGENT, 3))
                .isEqualTo(new WorkspaceSpaceUsage(WorkspaceSpaceKind.AGENT, 3, "집안일 비서", 0, 0, true));
    }

    @Test
    @DisplayName("번호가 0 으로 시작하는 디렉터리는 세지 않는다")
    void ignoresNumbersWithLeadingZero() throws IOException {
        write("u1/a.txt", 10);
        write("u01/b.txt", 20);
        write("u0/c.txt", 30);
        write("a03/d.txt", 40);

        WorkspaceUsageReport report = service(FIXED, 200_000).report();

        assertThat(report.spaces())
                .containsExactly(new WorkspaceSpaceUsage(WorkspaceSpaceKind.USER, 1, "아빠", 10, 1, false));
    }

    @Test
    @DisplayName("읽지 못한 디렉터리는 항목으로만 세고 그 아래는 건너뛰어 일부로 표시한다")
    void marksUnreadableDirectoryAsPartial() throws IOException {
        write("u1/own.txt", 10);
        Path locked = Files.createDirectories(root.resolve("u1/locked"));
        Files.write(locked.resolve("hidden.bin"), new byte[500]);
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            Assumptions.assumeFalse(Files.isReadable(locked), "root 로 돌면 권한을 빼도 읽힌다");

            WorkspaceSpaceUsage space =
                    service(FIXED, 200_000).report().spaces().get(0);

            assertThat(space.bytes()).isEqualTo(10);
            assertThat(space.entries()).isEqualTo(2);
            assertThat(space.partial()).isTrue();
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    @DisplayName("결과 어디에도 파일과 디렉터리 이름이 없다")
    void leavesFileNamesOut() throws IOException {
        write("u1/secret-plan.txt", 10);
        write("a3/private-notes/diary-entry.md", 20);

        WorkspaceUsageReport report = service(FIXED, 200_000).report();

        assertThat(report.spaces()).hasSize(2);
        assertThat(report.toString())
                .doesNotContain("secret-plan")
                .doesNotContain("private-notes")
                .doesNotContain("diary-entry")
                .doesNotContain(root.toString());
    }

    @Test
    @DisplayName("루트 설정이 비면 쓸 수 없고 목록이 비어 있다")
    void reportsUnavailableWithoutRoot() {
        WorkspaceUsageService service = new WorkspaceUsageService(
                LiveProperties.fixed(WorkspaceProperties.class, new WorkspaceProperties("", "")),
                userNames,
                agents,
                FIXED);

        assertThat(service.report()).isEqualTo(new WorkspaceUsageReport(false, List.of()));
    }

    private WorkspaceUsageService service(Clock clock, long maxEntries) {
        return new WorkspaceUsageService(
                LiveProperties.fixed(WorkspaceProperties.class, new WorkspaceProperties(root.toString(), "")),
                userNames,
                agents,
                clock,
                maxEntries,
                Duration.ofSeconds(30));
    }

    /** 종류와 번호로 공간 한 줄을 찾는다. 없으면 실패한다. */
    private static WorkspaceSpaceUsage space(WorkspaceUsageReport report, WorkspaceSpaceKind kind, long id) {
        return report.spaces().stream()
                .filter(each -> each.kind() == kind && each.id() == id)
                .findFirst()
                .orElseThrow(() -> new AssertionError(kind + " " + id + " 공간이 없다: " + report.spaces()));
    }

    /** 루트 아래 상대 경로에 크기만큼의 파일을 만든다. 중간 디렉터리도 만든다. */
    private void write(String relative, int size) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[size]);
    }

    /** 읽을 때마다 1밀리초씩 나아가는 시계다. 시간 상한이 0 이면 첫 공간부터 상한을 지난다. */
    private static final class SteppingClock extends Clock {
        private Instant now;

        private SteppingClock(Instant start) {
            this.now = start;
        }

        @Override
        public Instant instant() {
            Instant current = now;
            now = now.plusMillis(1);
            return current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
