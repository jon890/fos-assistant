package com.bifos.assistant.workspace.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.workspace.domain.WorkspaceDeletion;
import com.bifos.assistant.workspace.domain.WorkspaceEntryKind;
import com.bifos.assistant.workspace.domain.WorkspacePath;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 권한 도우미와의 계약을 가짜 도우미로 본다. 계약은 {@code backend/docs/code-architecture.md} 의 「지우기」 다.
 *
 * <p>unix socket 경로는 길이 상한이 있어 {@code /tmp} 아래 짧은 디렉터리에 둔다.
 */
class UnixSocketWorkspaceDeleterTest {

    private static final int MAX_ANSWER_BYTES = 64 * 1024;
    private static final WorkspacePath PATH = WorkspacePath.parse("reports/a.csv");

    private Path dir;
    private Path socket;
    private ServerSocketChannel server;
    private ExecutorService helper;

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createTempDirectory(Path.of("/tmp"), "ws");
        socket = dir.resolve("d.sock");
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        helper = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() throws Exception {
        helper.shutdownNow();
        helper.awaitTermination(5, TimeUnit.SECONDS);
        server.close();
        Files.deleteIfExists(socket);
        Files.deleteIfExists(dir);
    }

    @Test
    @Timeout(10)
    @DisplayName("요청 줄에 version, 주인 키, 경로, 항목 상한을 싣고 성공 답을 결과로 바꾼다")
    void sendsRequestLineAndReadsResult() throws Exception {
        Future<String> received = answerWith("{\"ok\":true,\"kind\":\"DIRECTORY\",\"entries\":3,\"bytes\":2048}\n");

        WorkspaceDeletion deletion = deleter(Duration.ofSeconds(5)).delete("u301", PATH, 10_000);

        assertThat(deletion).isEqualTo(new WorkspaceDeletion(WorkspaceEntryKind.DIRECTORY, 3, 2048));
        JsonNode request = JsonMapper.builder().build().readTree(received.get());
        assertThat(request.get("version").intValue()).isEqualTo(1);
        assertThat(request.get("owner").asString()).isEqualTo("u301");
        assertThat(request.get("path").asString()).isEqualTo("reports/a.csv");
        assertThat(request.get("max_entries").intValue()).isEqualTo(10_000);
    }

    @ParameterizedTest
    @Timeout(10)
    @CsvSource({
        "NOT_FOUND, WORKSPACE_ENTRY_NOT_FOUND",
        "LINK_IN_PATH, WORKSPACE_ENTRY_NOT_FOUND",
        "TOO_MANY_ENTRIES, WORKSPACE_DELETE_TOO_MANY",
        "FAILED, WORKSPACE_DELETE_FAILED",
        "INVALID_REQUEST, WORKSPACE_DELETE_FAILED",
        "SOMETHING_NEW, WORKSPACE_DELETE_FAILED"
    })
    @DisplayName("도우미의 실패 코드를 문서의 오류로 바꾼다")
    void mapsHelperFailureCodes(String helperCode, ErrorCode expected) {
        answerWith("{\"ok\":false,\"code\":\"" + helperCode + "\"}\n");

        assertFailsWith(deleter(Duration.ofSeconds(5)), expected);
    }

    @ParameterizedTest
    @Timeout(10)
    @ValueSource(
            strings = {
                "{not json\n",
                "[1]\n",
                "{\"ok\":true,\"kind\":\"PIPE\",\"entries\":1,\"bytes\":0}\n",
                "{\"ok\":true,\"kind\":\"FILE\",\"entries\":-1,\"bytes\":0}\n",
                ""
            })
    @DisplayName("깨진 답, 모르는 종류, 답 없이 닫기는 지우기 실패다")
    void rejectsMalformedAnswers(String answer) {
        answerWith(answer);

        assertFailsWith(deleter(Duration.ofSeconds(5)), ErrorCode.WORKSPACE_DELETE_FAILED);
    }

    @Test
    @Timeout(10)
    @DisplayName("답 한 줄은 64 KiB 까지 받는다")
    void acceptsAnswerAtLineLimit() {
        answerWith(paddedSuccess(MAX_ANSWER_BYTES) + "\n");

        WorkspaceDeletion deletion = deleter(Duration.ofSeconds(5)).delete("u301", PATH, 10_000);

        assertThat(deletion).isEqualTo(new WorkspaceDeletion(WorkspaceEntryKind.FILE, 1, 1));
    }

    @Test
    @Timeout(10)
    @DisplayName("64 KiB 를 넘는 답 한 줄은 지우기 실패다")
    void rejectsAnswerOverLineLimit() {
        answerWith(paddedSuccess(MAX_ANSWER_BYTES + 1) + "\n");

        assertFailsWith(deleter(Duration.ofSeconds(5)), ErrorCode.WORKSPACE_DELETE_FAILED);
    }

    @Test
    @Timeout(10)
    @DisplayName("답하지 않는 도우미는 제한 시간 뒤 지우기 실패다")
    void failsWhenHelperDoesNotAnswer() {
        answerWith(null);
        long started = System.nanoTime();

        assertFailsWith(deleter(Duration.ofMillis(300)), ErrorCode.WORKSPACE_DELETE_FAILED);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(300));
    }

    @Test
    @Timeout(10)
    @DisplayName("socket 에 연결하지 못하면 지우기 실패다")
    void failsWhenSocketIsMissing() {
        UnixSocketWorkspaceDeleter deleter = new UnixSocketWorkspaceDeleter(
                LiveProperties.fixed(
                        WorkspaceProperties.class,
                        new WorkspaceProperties("/", dir.resolve("none.sock").toString())),
                Duration.ofSeconds(5));

        assertFailsWith(deleter, ErrorCode.WORKSPACE_DELETE_FAILED);
    }

    @Test
    @Timeout(10)
    @DisplayName("socket 설정값이 경로가 될 수 없는 문자열이면 500 이 아니라 지우기 실패다")
    void failsWhenSocketSettingIsInvalid() {
        UnixSocketWorkspaceDeleter deleter = new UnixSocketWorkspaceDeleter(
                LiveProperties.fixed(WorkspaceProperties.class, new WorkspaceProperties("/", "/tmp/bad\0.sock")),
                Duration.ofSeconds(5));

        assertFailsWith(deleter, ErrorCode.WORKSPACE_DELETE_FAILED);
    }

    @Test
    @Timeout(10)
    @DisplayName("socket 설정이 비면 지우기를 쓸 수 없다")
    void rejectsWhenSocketIsNotConfigured() {
        UnixSocketWorkspaceDeleter deleter = new UnixSocketWorkspaceDeleter(
                LiveProperties.fixed(WorkspaceProperties.class, new WorkspaceProperties("/", "")));

        assertFailsWith(deleter, ErrorCode.WORKSPACE_DELETE_UNAVAILABLE);
    }

    private UnixSocketWorkspaceDeleter deleter(Duration timeout) {
        return new UnixSocketWorkspaceDeleter(
                LiveProperties.fixed(WorkspaceProperties.class, new WorkspaceProperties("/", socket.toString())),
                timeout);
    }

    private static void assertFailsWith(UnixSocketWorkspaceDeleter deleter, ErrorCode expected) {
        assertThatThrownBy(() -> deleter.delete("u301", PATH, 10_000))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(expected);
    }

    /** 줄바꿈을 뺀 길이가 {@code length} 바이트인 성공 답이다. */
    private static String paddedSuccess(int length) {
        String head = "{\"ok\":true,\"kind\":\"FILE\",\"entries\":1,\"bytes\":1,\"pad\":\"";
        String tail = "\"}";
        return head + "a".repeat(length - head.length() - tail.length()) + tail;
    }

    /**
     * 가짜 도우미가 연결 하나를 받아 요청 줄을 읽고 {@code answer} 를 보낸 뒤 닫는다. {@code null} 이면 답하지 않고 상대가 닫을 때까지
     * 둔다.
     *
     * @return 받은 요청 줄. 줄바꿈은 뺀다
     */
    private Future<String> answerWith(String answer) {
        return helper.submit(() -> {
            try (SocketChannel client = server.accept()) {
                String request = readLine(client);
                if (answer == null) {
                    while (client.read(ByteBuffer.allocate(1)) >= 0) {
                        // 상대가 닫을 때까지 읽는다.
                    }
                    return request;
                }
                ByteBuffer out = ByteBuffer.wrap(answer.getBytes(StandardCharsets.UTF_8));
                try {
                    while (out.hasRemaining()) {
                        client.write(out);
                    }
                } catch (IOException ex) {
                    // 긴 답을 보내는 도중 상대가 끊는 것은 시험이 기대한 동작이다.
                }
                return request;
            }
        });
    }

    private static String readLine(SocketChannel client) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        ByteBuffer one = ByteBuffer.allocate(1);
        while (client.read(one) > 0) {
            one.flip();
            byte b = one.get();
            if (b == '\n') {
                break;
            }
            line.write(b);
            one.clear();
        }
        return line.toString(StandardCharsets.UTF_8);
    }
}
