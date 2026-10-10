package com.bifos.assistant.workspace.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.workspace.domain.WorkspaceCursor;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 실행 공간을 읽을 때 링크와 특수 파일과 남의 공간을 어떻게 다루는지 실제 임시 디렉터리로 본다. */
class WorkspaceTreeTest {

    @TempDir
    Path root;

    private Path owner;
    private Path sibling;

    @Test
    @DisplayName("1,002개를 역순으로 만들어도 첫 페이지는 전체 정렬의 앞 1,000개다")
    void selectsFirstPageFromEntireDirectory() throws IOException {
        for (int i = 1_001; i >= 0; i--) {
            Files.createFile(owner.resolve(String.format("f%04d", i)));
        }
        List<String> expected = IntStream.range(0, 1_000)
                .mapToObj(i -> String.format("f%04d", i))
                .toList();
        assertThat(list(owner, "").orElseThrow().entries())
                .extracting(WorkspaceEntry::name)
                .containsExactlyElementsOf(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "0,false",
        "1,false",
        "999,false",
        "1000,false",
        "1001,false",
        "1002,false",
        "10000,false",
        "0,true",
        "1,true",
        "999,true",
        "1000,true",
        "1001,true",
        "1002,true",
        "10000,true"
    })
    @DisplayName("역순과 무작위 생성 목록을 secure와 대체 경로 모두 끝까지 누락과 중복 없이 탐색한다")
    void reachesEveryEntryInFullSortOrder(int count, boolean shuffled) throws IOException {
        List<WorkspaceEntry> expected = new ArrayList<>();
        List<Integer> order = new ArrayList<>(IntStream.range(0, count).boxed().toList());
        if (shuffled) {
            Collections.shuffle(order, new Random(371));
        } else {
            Collections.reverse(order);
        }
        for (int i : order) {
            String name = String.format(
                    "%05d-%s",
                    i,
                    switch (i % 4) {
                        case 0 -> "대문자A";
                        case 1 -> "소문자a";
                        case 2 -> "é";
                        default -> "e\u0301";
                    });
            boolean directory = i % 7 == 0;
            if (directory) {
                Files.createDirectory(owner.resolve(name));
            } else {
                Files.createFile(owner.resolve(name));
            }
            expected.add(testEntry(name, directory));
        }
        Comparator<WorkspaceEntry> sorted = Comparator.comparing(
                        (WorkspaceEntry entry) -> entry.kind() != WorkspaceEntryKind.DIRECTORY)
                .thenComparing(WorkspaceEntry::name);
        expected.sort(sorted);
        for (boolean checked : new boolean[] {false, true}) {
            List<String> actual = new ArrayList<>();
            String cursor = null;
            int pages = 0;
            do {
                WorkspaceListing page = WorkspaceTree.list(
                                owner, WorkspacePath.parse(""), 1_000, cursor, checked, () -> {}, System::nanoTime)
                        .orElseThrow();
                assertThat(page.entries()).hasSizeLessThanOrEqualTo(1_000);
                assertThat(page.truncated()).isEqualTo(page.nextCursor() != null);
                actual.addAll(page.entries().stream().map(WorkspaceEntry::name).toList());
                cursor = page.nextCursor();
                assertThat(++pages).isLessThanOrEqualTo(11);
            } while (cursor != null);
            assertThat(actual)
                    .containsExactlyElementsOf(
                            expected.stream().map(WorkspaceEntry::name).toList());
            assertThat(actual).doesNotHaveDuplicates();
        }
    }

    @Test
    @DisplayName("같은 이름의 대소문자와 Unicode 정규화 차이를 cursor에서 합치지 않는다")
    void preservesRawCaseAndUnicodeSortKeys() throws IOException {
        List<String> names = List.of("A", "a", "é", "e\u0301", "한글");
        for (String name : names) {
            Assumptions.assumeFalse(Files.exists(owner.resolve(name)), "이 파일 시스템은 이름을 정규화한다");
            Files.createFile(owner.resolve(name));
        }
        List<String> actual = new ArrayList<>();
        String cursor = null;
        do {
            WorkspaceListing page = WorkspaceTree.list(owner, WorkspacePath.parse(""), 1, cursor)
                    .orElseThrow();
            actual.add(page.entries().get(0).name());
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(actual).containsExactlyElementsOf(names.stream().sorted().toList());
    }

    @Test
    @DisplayName("Linux의 역슬래시와 주소 불가능 이름을 cursor 정렬 값에서 누락하지 않는다")
    void pagesUnaddressableNames() throws IOException {
        Files.createFile(owner.resolve("a\\b"));
        Files.createFile(owner.resolve("c%"));
        Files.createFile(owner.resolve("d\n"));
        WorkspaceListing first = WorkspaceTree.list(owner, WorkspacePath.parse(""), 1, (String) null)
                .orElseThrow();
        assertThat(first.entries().get(0).name()).isEqualTo("a\\b");
        assertThat(first.entries().get(0).openable()).isFalse();
        WorkspaceListing second = WorkspaceTree.list(owner, WorkspacePath.parse(""), 1, first.nextCursor())
                .orElseThrow();
        WorkspaceListing third = WorkspaceTree.list(owner, WorkspacePath.parse(""), 1, second.nextCursor())
                .orElseThrow();
        assertThat(second.entries()).extracting(WorkspaceEntry::name).containsExactly("c%");
        assertThat(third.entries()).extracting(WorkspaceEntry::name).containsExactly("d\n");
        assertThat(third.nextCursor()).isNull();
    }

    @Test
    @DisplayName("다음 페이지는 마지막 표시 키 뒤에서 시작하고 생성 삭제 이름 변경은 best-effort로 다룬다")
    void continuesAfterShownKeyDuringChanges() throws IOException {
        for (String name : List.of("b", "d", "f", "h")) {
            Files.createFile(owner.resolve(name));
        }
        WorkspacePath path = WorkspacePath.parse("");
        WorkspaceListing first =
                WorkspaceTree.list(owner, path, 2, (String) null).orElseThrow();
        assertThat(WorkspaceCursor.decode(first.nextCursor(), path).name()).isEqualTo("d");
        Files.createFile(owner.resolve("a"));
        Files.createFile(owner.resolve("e"));
        Files.delete(owner.resolve("f"));
        Files.move(owner.resolve("b"), owner.resolve("g"));
        WorkspaceListing second =
                WorkspaceTree.list(owner, path, 2, first.nextCursor()).orElseThrow();
        assertThat(second.entries()).extracting(WorkspaceEntry::name).containsExactly("e", "g");
        Files.delete(owner.resolve("h"));
        WorkspaceListing empty =
                WorkspaceTree.list(owner, path, 2, second.nextCursor()).orElseThrow();
        assertThat(empty.entries()).isEmpty();
        assertThat(empty.nextCursor()).isNull();
        assertThat(WorkspaceTree.list(owner, path, 2).orElseThrow().entries())
                .extracting(WorkspaceEntry::name)
                .containsExactly("a", "d");
    }

    @Test
    @DisplayName("시간 예산을 넘으면 일부 목록 대신 IOException으로 실패한다")
    void failsAtDeadlineWithoutPartialPage() throws IOException {
        Files.createFile(owner.resolve("a"));
        for (boolean checked : new boolean[] {false, true}) {
            AtomicLong clock = new AtomicLong();
            assertThatThrownBy(() -> WorkspaceTree.list(
                            owner,
                            WorkspacePath.parse(""),
                            1,
                            null,
                            checked,
                            () -> {},
                            () -> clock.getAndAdd(2_000_000_000L)))
                    .isInstanceOf(IOException.class);
            assertThat(list(owner, "").orElseThrow().entries()).hasSize(1);
        }
    }

    @Test
    @DisplayName("최대 힙은 10,000건을 넣어도 lookahead 포함 1,001건을 넘지 않는다")
    void boundsHeapIncludingLookahead() {
        PriorityQueue<WorkspaceEntry> heap =
                new PriorityQueue<>(Comparator.comparing(WorkspaceEntry::name).reversed());
        int maximum = 0;
        for (int i = 9_999; i >= 0; i--) {
            WorkspaceTree.retainNext(heap, testEntry(String.format("f%05d", i), false), 1_000);
            maximum = Math.max(maximum, heap.size());
            assertThat(heap).hasSizeLessThanOrEqualTo(1_001);
        }
        assertThat(maximum).isEqualTo(1_001);
        assertThat(heap.element().name()).isEqualTo("f01000");
    }

    @Test
    @DisplayName("최대 4,096바이트 경로와 255바이트 이름의 cursor는 4,096자 안에서 왕복한다")
    void roundTripsMaximumPathAndNameWithoutEmbeddingPath() {
        String raw = String.join("/", Collections.nCopies(15, "x".repeat(255))) + "/" + "x".repeat(254) + "/x";
        assertThat(raw.getBytes(StandardCharsets.UTF_8)).hasSize(4_096);
        WorkspacePath path = WorkspacePath.parse(raw);
        String cursor = WorkspaceCursor.encode(path, testEntry("한".repeat(85), false));
        assertThat(cursor.length()).isLessThanOrEqualTo(4_096);
        assertThat(WorkspaceCursor.decode(cursor, path).name()).isEqualTo("한".repeat(85));
        assertThat(Base64.getUrlEncoder()
                        .encodeToString(raw.getBytes(StandardCharsets.UTF_8))
                        .length())
                .isGreaterThan(4_096);
    }

    @Test
    @DisplayName("실제 파일 시스템에서 가능한 긴 경로도 다음 페이지로 이어진다")
    void pagesPhysicalLongPath() throws IOException {
        String segment = "x".repeat(255);
        Path dir = owner;
        List<String> segments = new ArrayList<>();
        // macOS는 전체 경로 한도가 더 작다. codec의 최대 경로 검사는 별도로 한다.
        int depth = System.getProperty("os.name").equals("Linux") ? 13 : 2;
        for (int i = 0; i < depth; i++) {
            segments.add(segment);
            dir = Files.createDirectory(dir.resolve(segment));
        }
        WorkspacePath path = WorkspacePath.ofSegments(segments);
        if (depth == 13) {
            assertThat(path.value().getBytes(StandardCharsets.UTF_8)).hasSize(3_327);
        }
        Files.createFile(dir.resolve("a".repeat(255)));
        Files.createFile(dir.resolve("z".repeat(255)));
        WorkspaceListing first = WorkspaceTree.list(owner, path, 1).orElseThrow();
        assertThat(WorkspaceTree.list(owner, path, 1, first.nextCursor())
                        .orElseThrow()
                        .entries())
                .extracting(WorkspaceEntry::name)
                .containsExactly("z".repeat(255));
    }

    @Test
    @DisplayName("cursor는 canonical base64url과 strict UTF-8 JSON 및 정확한 키 타입 버전을 검사한다")
    void rejectsMalformedCursors() {
        WorkspacePath path = WorkspacePath.parse("");
        String valid = WorkspaceCursor.encode(path, testEntry("a", false));
        String json = new String(Base64.getUrlDecoder().decode(valid), StandardCharsets.UTF_8);
        List<String> invalid = new ArrayList<>(List.of(
                "",
                "!",
                "a",
                valid + "=",
                "a".repeat(4_097),
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[] {(byte) 0xc0, (byte) 0xaf})));
        for (String malformed : List.of(
                "[]",
                json + "{}",
                json.replace("\"version\":1", "\"version\":2"),
                json.replace("\"version\":1", "\"version\":1.0"),
                json.replace("\"version\":1", "\"version\":\"1\""),
                json.replace("\"directory\":false", "\"directory\":0"),
                json.replace("\"name\":\"a\"", "\"name\":null"),
                json.replace("\"name\":\"a\"", "\"name\":\"a/b\""),
                json.replace("\"name\":\"a\"", "\"name\":\"..\""),
                json.replace("\"name\":\"a\"", "\"name\":\"\\uD800\""),
                json.replace("\"name\":\"a\"", "\"name\":\"\\u0000\""),
                json.replace("\"name\":\"a\"", "\"name\":\"" + "x".repeat(256) + "\""),
                json.replaceFirst("\\{", "{\"extra\":1,"),
                json.replaceFirst("\\{", "{\"version\":1,"),
                json.replace("pathHash", "other"))) {
            invalid.add(
                    Base64.getUrlEncoder().withoutPadding().encodeToString(malformed.getBytes(StandardCharsets.UTF_8)));
        }
        for (String cursor : invalid) {
            assertCode(() -> WorkspaceCursor.decode(cursor, path), ErrorCode.VALIDATION_FAILED);
        }
        assertCode(() -> WorkspaceCursor.decode(valid, WorkspacePath.parse("other")), ErrorCode.VALIDATION_FAILED);
        assertCode(
                () -> WorkspaceCursor.decode(
                        WorkspaceCursor.encode(WorkspacePath.parse("A"), testEntry("a", false)),
                        WorkspacePath.parse("a")),
                ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("10,000건의 한 페이지 조회 지연과 힙 차이를 합성 측정한다")
    void measuresSyntheticTenThousandEntryPage() throws IOException {
        for (int i = 0; i < 10_000; i++) {
            Files.createFile(owner.resolve(String.format("f%05d", i)));
        }
        List<Long> millis = new ArrayList<>();
        Runtime runtime = Runtime.getRuntime();
        long before = runtime.totalMemory() - runtime.freeMemory();
        for (int i = 0; i < 10; i++) {
            long start = System.nanoTime();
            assertThat(list(owner, "").orElseThrow().entries()).hasSize(1_000);
            millis.add((System.nanoTime() - start) / 1_000_000);
        }
        long delta = runtime.totalMemory() - runtime.freeMemory() - before;
        Collections.sort(millis);
        System.out.printf(
                "synthetic listing samples=10 p50_ms=%d p95_ms=%d heap_delta_bytes=%d max_candidates=1001%n",
                millis.get(4), millis.get(9), delta);
    }

    private static WorkspaceEntry testEntry(String name, boolean directory) {
        return new WorkspaceEntry(
                name,
                directory ? WorkspaceEntryKind.DIRECTORY : WorkspaceEntryKind.FILE,
                directory ? null : 0L,
                Instant.EPOCH,
                true,
                WorkspacePath.addressable(name));
    }

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

    @ParameterizedTest(name = "대체 경로 강제 = {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("공간 밖을 가리키는 링크 파일은 LINK 이고 읽지 않는다")
    void doesNotFollowFileLink(boolean checkedPath) throws IOException {
        Path outside = Files.writeString(root.resolve("outside.txt"), "outside");
        Files.createSymbolicLink(owner.resolve("link.txt"), outside);

        WorkspaceEntry entry =
                list(owner, "", checkedPath).orElseThrow().entries().get(0);

        assertThat(entry.kind()).isEqualTo(WorkspaceEntryKind.LINK);
        assertThat(entry.readable()).isFalse();
        assertThat(entry.size()).isNull();
        assertCode(() -> open(owner, "link.txt", checkedPath), ErrorCode.WORKSPACE_ENTRY_NOT_FOUND);
    }

    @ParameterizedTest(name = "대체 경로 강제 = {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("형제 주인 디렉터리를 가리키는 링크 디렉터리 아래는 목록과 본문 모두 닿지 않는다")
    void doesNotFollowDirectoryLinkToSibling(boolean checkedPath) throws IOException {
        Path link = Files.createSymbolicLink(owner.resolve("peek"), sibling);

        WorkspaceListing top = list(owner, "", checkedPath).orElseThrow();
        assertThat(top.entries()).extracting(WorkspaceEntry::name).containsExactly("peek");
        assertThat(top.entries().get(0).kind()).isEqualTo(WorkspaceEntryKind.LINK);
        assertThat(list(owner, "peek", checkedPath)).isEmpty();
        assertCode(() -> open(owner, "peek/secret.txt", checkedPath), ErrorCode.WORKSPACE_ENTRY_NOT_FOUND);
        assertThat(WorkspaceTree.stat(owner, WorkspacePath.parse("peek/secret.txt"), checkedPath, () -> {}))
                .isEmpty();
        assertThat(WorkspaceTree.isDirectoryNoFollow(link)).isFalse();
    }

    @ParameterizedTest(name = "대체 경로 강제 = {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("주인 디렉터리 자체가 링크면 비어 있다")
    void refusesOwnerDirectoryLink(boolean checkedPath) throws IOException {
        Path linkedOwner = Files.createSymbolicLink(root.resolve("u3"), sibling);

        assertThat(list(linkedOwner, "", checkedPath)).isEmpty();
        assertThat(WorkspaceTree.isDirectoryNoFollow(linkedOwner)).isFalse();
        assertCode(() -> open(linkedOwner, "secret.txt", checkedPath), ErrorCode.WORKSPACE_ENTRY_NOT_FOUND);
    }

    @ParameterizedTest(name = "대체 경로 강제 = {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("하드 링크가 둘 이상인 파일은 읽을 수 없다")
    void refusesHardLinkedFile(boolean checkedPath) throws IOException {
        Path original = Files.writeString(owner.resolve("a.txt"), "shared");
        Files.createLink(owner.resolve("b.txt"), original);

        assertThat(list(owner, "", checkedPath).orElseThrow().entries()).allSatisfy(entry -> {
            assertThat(entry.kind()).isEqualTo(WorkspaceEntryKind.FILE);
            assertThat(entry.readable()).isFalse();
        });
        assertCode(() -> open(owner, "a.txt", checkedPath), ErrorCode.WORKSPACE_ENTRY_UNREADABLE);
    }

    @ParameterizedTest(name = "대체 경로 강제 = {0}")
    @ValueSource(booleans = {true, false})
    @Timeout(5)
    @DisplayName("FIFO 는 OTHER 이고 열지 않고 없는 것으로 답한다")
    void refusesFifoWithoutBlocking(boolean checkedPath) throws Exception {
        Path fifo = owner.resolve("pipe");
        Assumptions.assumeTrue(mkfifo(fifo), "mkfifo 가 없는 환경이다");

        assertThat(list(owner, "", checkedPath).orElseThrow().entries().get(0).kind())
                .isEqualTo(WorkspaceEntryKind.OTHER);
        assertCode(() -> open(owner, "pipe", checkedPath), ErrorCode.WORKSPACE_ENTRY_NOT_FOUND);
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

    /** {@code checkedPath} 가 참이면 운영 이미지(musl)가 타는 대체 경로를 강제한다. */
    private static Optional<WorkspaceListing> list(Path ownerDir, String path, boolean checkedPath) throws IOException {
        return WorkspaceTree.list(ownerDir, WorkspacePath.parse(path), 1_000, checkedPath, () -> {});
    }

    private static WorkspaceOpenedFile open(Path ownerDir, String path, boolean checkedPath) throws IOException {
        return WorkspaceTree.open(ownerDir, WorkspacePath.parse(path), checkedPath, () -> {});
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
